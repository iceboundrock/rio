import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { CreateCardTransactionsFormView, emptyRow, validateRows, type FormRow } from "./CreateCardTransactionsForm";

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

  it("starts with an empty USD debit row", () => {
    expect(emptyRow()).toEqual({ description: "", amount: "", currency: "USD", type: "DEBIT" });
  });
});
