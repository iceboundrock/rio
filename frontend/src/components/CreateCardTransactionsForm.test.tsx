import { afterEach, describe, expect, it, vi } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { NetworkError } from "../api/client";
import { createCardTransactions } from "../api/cardTransactions";
import { CreateCardTransactionsFormView, emptyRow, submissionFor, validateRows, type FormRow, type Submission } from "./CreateCardTransactionsForm";

const lunch: FormRow = { description: "Lunch", amount: "18.00", currency: "USD", type: "DEBIT" };
const ramen: FormRow = { description: "Ramen", amount: "1200", currency: "JPY", type: "CREDIT" };

const noop = () => undefined;

function render(overrides: Partial<Parameters<typeof CreateCardTransactionsFormView>[0]> = {}) {
  return renderToStaticMarkup(
    <CreateCardTransactionsFormView
      rows={[lunch, ramen]}
      rowErrors={[null, null]}
      error={null}
      submitting={false}
      onChangeRow={noop}
      onAddRow={noop}
      onRemoveRow={noop}
      onSubmit={noop}
      {...overrides}
    />,
  );
}

describe("create card transactions form view", () => {
  it("renders one row per entry with its current values", () => {
    const html = render();
    expect(html.match(/class="form-row"/g)).toHaveLength(2);
    expect(html).toContain('value="Lunch"');
    expect(html).toContain('value="1200"');
  });

  it("disables submit while submitting and shows row and form errors", () => {
    const html = render({ submitting: true, rowErrors: [null, "Enter a positive amount"], error: "Request failed (400 VALIDATION_ERROR): description must not be blank" });
    expect(html).toMatch(/<button[^>]*type="submit"[^>]*disabled/);
    expect(html).toContain("Enter a positive amount");
    expect(html).toContain("description must not be blank");
  });
});

describe("validateRows", () => {
  it("converts valid rows to inputs with bigint money", () => {
    const { inputs, rowErrors } = validateRows([lunch, ramen]);
    expect(rowErrors).toEqual([null, null]);
    expect(inputs).toEqual([
      { description: "Lunch", amount: { amount: 1800n, currency: "USD" }, type: "DEBIT" },
      { description: "Ramen", amount: { amount: 1200n, currency: "JPY" }, type: "CREDIT" },
    ]);
  });

  it("reports a message per invalid row and no inputs", () => {
    const { inputs, rowErrors } = validateRows([{ ...lunch, description: "   " }, { ...ramen, amount: "12.5" }, lunch]);
    expect(inputs).toBeNull();
    expect(rowErrors[0]).toMatch(/description/i);
    expect(rowErrors[1]).toMatch(/amount/i);
    expect(rowErrors[2]).toBeNull();
  });

  it("accepts amounts up to the signed 64-bit wire limit and rejects anything above it per row", () => {
    const max = validateRows([{ ...lunch, amount: "92233720368547758.07" }, { ...ramen, amount: "9223372036854775807" }]);
    expect(max.rowErrors).toEqual([null, null]);
    expect(max.inputs?.map((i) => i.amount.amount)).toEqual([9223372036854775807n, 9223372036854775807n]);

    const over = validateRows([
      { ...lunch, amount: "92233720368547758.08" }, // one minor unit above Long.MAX_VALUE
      { ...ramen, amount: "9999999999999999999" }, // 19 digits, above Long.MAX_VALUE
      { ...lunch, amount: "100000000000000000000" }, // 20 digits
      lunch,
    ]);
    expect(over.inputs).toBeNull();
    expect(over.rowErrors).toEqual(["Amount is too large.", "Amount is too large.", "Amount is too large.", null]);
  });

  it("starts with an empty USD debit row", () => {
    expect(emptyRow()).toEqual({ description: "", amount: "", currency: "USD", type: "DEBIT" });
  });
});

describe("submissionFor", () => {
  afterEach(() => vi.unstubAllGlobals());

  const inputsOf = (rows: FormRow[]) => validateRows(rows).inputs!;

  it("mints a key for the first attempt and reuses it while the validated request is unchanged", () => {
    const first = submissionFor(null, inputsOf([lunch, ramen]));
    expect(first.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    // Same logical request, even if the draft was retyped with only cosmetic differences.
    const retry = submissionFor(first, inputsOf([{ ...lunch, description: " Lunch ", amount: "18.0" }, ramen]));
    expect(retry.idempotencyKey).toBe(first.idempotencyKey);
  });

  it("mints a new key when the request differs in any field, order or length", () => {
    const first = submissionFor(null, inputsOf([lunch, ramen]));
    const variants: FormRow[][] = [
      [{ ...lunch, description: "Dinner" }, ramen],
      [{ ...lunch, amount: "18.01" }, ramen],
      [{ ...lunch, currency: "EUR" }, ramen],
      [{ ...lunch, type: "CREDIT" }, ramen],
      [ramen, lunch],
      [lunch],
      [lunch, ramen, lunch],
    ];
    for (const rows of variants) {
      expect(submissionFor(first, inputsOf(rows)).idempotencyKey).not.toBe(first.idempotencyKey);
    }
  });

  it("retries an unchanged draft with the same Idempotency-Key so a lost response replays instead of creating twice", async () => {
    const created = { id: "tx-1", description: "Lunch", amount: { amount: "1800", currency: "USD" }, type: "DEBIT", status: "COMPLETED", createdAt: "2026-09-02T15:30:00Z" };
    const fetch = vi
      .fn()
      .mockRejectedValueOnce(new TypeError("Failed to fetch")) // the server may have committed; the response never arrived
      .mockResolvedValueOnce(new Response(JSON.stringify({ items: [created] }), { status: 201 }));
    vi.stubGlobal("fetch", fetch);

    let pending: Submission | null = submissionFor(null, inputsOf([lunch]));
    await expect(createCardTransactions(pending.inputs, pending.idempotencyKey)).rejects.toBeInstanceOf(NetworkError);

    // The user clicks Create again with the draft untouched.
    pending = submissionFor(pending, inputsOf([lunch]));
    const result = await createCardTransactions(pending.inputs, pending.idempotencyKey);

    expect(result.map((r) => r.id)).toEqual(["tx-1"]);
    const keys = fetch.mock.calls.map(([, init]) => (init.headers as Record<string, string>)["Idempotency-Key"]);
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });
});
