import { afterEach, describe, expect, it, vi } from "vitest";
import moneySchema from "@schemas/money.schema.json";
import { ApiContractError, ApiError, RequestContractError } from "./client";
import { describeError } from "./errors";
import { createCardTransaction, createCardTransactions, getCardTransaction, getCardTransactions } from "./cardTransactions";
import { CURRENCIES, formatMoney, type CurrencyCode } from "../money/money";

const cardTransaction = {
  id: "tx-1",
  description: "Test",
  amount: { amount: "9223372036854775807", currency: "USD" },
  type: "CREDIT",
  status: "COMPLETED",
  createdAt: "2026-09-02T15:30:00.123456789Z",
};

function respond(body: unknown, status = 200) {
  const fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status }));
  vi.stubGlobal("fetch", fetch);
  return fetch;
}

afterEach(() => vi.unstubAllGlobals());

describe("card transaction API boundary", () => {
  it("maps every schema currency exactly and provides formatting metadata", async () => {
    expect(Object.keys(CURRENCIES).sort()).toEqual([...moneySchema.properties.currency.enum].sort());
    const expected: Record<CurrencyCode, string> = {
      BRL: "R$1.25", CAD: "CA$1.25", CNY: "CN¥1.25", EUR: "€1.25", JPY: "¥125", USD: "$1.25",
    };
    for (const currency of Object.keys(expected) as CurrencyCode[]) {
      respond({ items: [{ ...cardTransaction, amount: { ...cardTransaction.amount, currency } }] });
      const [result] = await getCardTransactions();
      expect(result.amount).toEqual({ amount: 9223372036854775807n, currency });
      expect(result.createdAt.toISOString()).toBe("2026-09-02T15:30:00.123Z");
      expect(result.createdAtInstant).toBe("2026-09-02T15:30:00.123456789Z");
      expect(formatMoney({ amount: 125n, currency })).toBe(expected[currency]);
    }
  });

  it("encodes detail IDs and maps valid responses", async () => {
    const fetch = respond(cardTransaction);
    expect((await getCardTransaction("a/b")).amount.amount).toBe(9223372036854775807n);
    expect(fetch).toHaveBeenCalledWith("/api/card-transactions/a%2Fb", expect.anything());
  });

  it("serializes POST money as an exact string and validates its response", async () => {
    const fetch = respond(cardTransaction, 201);
    const result = await createCardTransaction({ description: "Test", amount: { amount: 9223372036854775807n, currency: "USD" }, type: "CREDIT" });
    expect(result.amount.amount).toBe(9223372036854775807n);
    expect(JSON.parse(fetch.mock.calls[0][1].body).amount.amount).toBe("9223372036854775807");
    respond({ ...cardTransaction, amount: { amount: 125, currency: "USD" } }, 201);
    await expect(createCardTransaction({ description: "Test", amount: { amount: 125n, currency: "USD" }, type: "CREDIT" })).rejects.toBeInstanceOf(ApiContractError);
  });

  it("describes a request that violates the contract without dumping the request body", async () => {
    const fetch = respond(cardTransaction, 201);
    // 21 digits: over the schema's maxLength, so the guard fires before fetch.
    const input = { description: "Rent", amount: { amount: 100000000000000000000n, currency: "USD" as const }, type: "DEBIT" as const };
    for (const call of [() => createCardTransaction(input), () => createCardTransactions([input])]) {
      const error = await call().catch((e: unknown) => e);
      expect(error).toBeInstanceOf(RequestContractError);
      expect(describeError(error)).toMatch(/^The request was not sent\. Request to \/api\/card-transactions violates the API contract: .*amount\/amount must NOT have more than 19 characters/);
      expect(describeError(error)).not.toContain("Rent");
      expect(describeError(error)).not.toContain("100000000000000000000");
    }
    expect(fetch).not.toHaveBeenCalled();
  });

  it("posts an array for a batch and maps every returned item", async () => {
    const second = { ...cardTransaction, id: "tx-2", amount: { amount: "1200", currency: "JPY" }, type: "DEBIT" };
    const fetch = respond({ items: [cardTransaction, second] }, 201);
    const inputs = [
      { description: "Test", amount: { amount: 9223372036854775807n, currency: "USD" as const }, type: "CREDIT" as const },
      { description: "Ramen", amount: { amount: 1200n, currency: "JPY" as const }, type: "DEBIT" as const },
    ];
    const result = await createCardTransactions(inputs);
    expect(result.map((r) => r.id)).toEqual(["tx-1", "tx-2"]);
    expect(result[1].amount).toEqual({ amount: 1200n, currency: "JPY" });
    const [url, init] = fetch.mock.calls[0];
    expect(url).toBe("/api/card-transactions");
    expect(JSON.parse(init.body)).toEqual([
      { description: "Test", amount: { amount: "9223372036854775807", currency: "USD" }, type: "CREDIT" },
      { description: "Ramen", amount: { amount: "1200", currency: "JPY" }, type: "DEBIT" },
    ]);
  });

  it("refuses to send an empty batch and rejects a batch response that violates the contract", async () => {
    const fetch = respond({ items: [] }, 201);
    await expect(createCardTransactions([])).rejects.toThrow(/violates the API contract/);
    expect(fetch).not.toHaveBeenCalled();
    respond(cardTransaction, 201); // a bare object is not the list shape
    await expect(createCardTransactions([{ description: "Test", amount: { amount: 1n, currency: "USD" }, type: "DEBIT" }])).rejects.toBeInstanceOf(ApiContractError);
  });

  it("rejects malformed list and detail shapes", async () => {
    respond({ items: [{ ...cardTransaction, amount: { amount: 125, currency: "USD" } }] });
    await expect(getCardTransactions()).rejects.toBeInstanceOf(ApiContractError);
    respond({ ...cardTransaction, status: "UNKNOWN" });
    await expect(getCardTransaction("tx-1")).rejects.toBeInstanceOf(ApiContractError);
  });

  it.each(["2026-99-99T00:00:00Z", "2026-02-30T00:00:00Z", "2026-09-02T24:00:00Z", "2025-02-29T12:00:00Z"])("rejects impossible instant %s", async (createdAt) => {
    respond({ ...cardTransaction, createdAt });
    await expect(getCardTransaction("tx-1")).rejects.toBeInstanceOf(ApiContractError);
    respond({ items: [{ ...cardTransaction, createdAt }] });
    await expect(getCardTransactions()).rejects.toBeInstanceOf(ApiContractError);
  });

  it.each(["2024-02-29T12:00:00Z", "2026-09-02T00:00:00.000000001Z"])("accepts valid instant %s and keeps it exactly", async (createdAt) => {
    respond({ ...cardTransaction, createdAt });
    const result = await getCardTransaction("tx-1");
    expect(result.createdAt.getTime()).toBe(new Date(createdAt).getTime());
    expect(result.createdAtInstant).toBe(createdAt);
  });

  it("distinguishes server errors, malformed error bodies, and non-JSON success", async () => {
    respond({ code: "NOT_FOUND", message: "missing" }, 404);
    await expect(getCardTransaction("missing")).rejects.toMatchObject({ name: "ApiError", status: 404, code: "NOT_FOUND" });
    respond({ code: "OTHER", message: "unexpected" }, 500);
    await expect(getCardTransactions()).rejects.toBeInstanceOf(ApiContractError);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("not JSON")));
    await expect(getCardTransactions()).rejects.toBeInstanceOf(ApiContractError);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("proxy unavailable", { status: 502 })));
    await expect(getCardTransactions()).rejects.toBeInstanceOf(ApiError);
  });
});
