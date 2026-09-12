import { afterEach, describe, expect, it, vi } from "vitest";
import moneySchema from "@schemas/money.schema.json";
import { ApiContractError, ApiError } from "./client";
import { createTransaction, getTransaction, getTransactions } from "./transactions";
import { CURRENCIES, formatMoney, type CurrencyCode } from "../money/money";

const transaction = {
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

describe("transaction API boundary", () => {
  it("maps every schema currency exactly and provides formatting metadata", async () => {
    expect(Object.keys(CURRENCIES).sort()).toEqual([...moneySchema.properties.currency.enum].sort());
    const expected: Record<CurrencyCode, string> = {
      BRL: "R$1.25", CAD: "CA$1.25", CNY: "CN¥1.25", EUR: "€1.25", JPY: "¥125", USD: "$1.25",
    };
    for (const currency of Object.keys(expected) as CurrencyCode[]) {
      respond({ items: [{ ...transaction, amount: { ...transaction.amount, currency } }] });
      const [result] = await getTransactions();
      expect(result.amount).toEqual({ amount: 9223372036854775807n, currency });
      expect(result.createdAt.toISOString()).toBe("2026-09-02T15:30:00.123Z");
      expect(formatMoney({ amount: 125n, currency })).toBe(expected[currency]);
    }
  });

  it("encodes detail IDs and maps valid responses", async () => {
    const fetch = respond(transaction);
    expect((await getTransaction("a/b")).amount.amount).toBe(9223372036854775807n);
    expect(fetch).toHaveBeenCalledWith("/api/transactions/a%2Fb", expect.anything());
  });

  it("serializes POST money as an exact string and validates its response", async () => {
    const fetch = respond(transaction, 201);
    const result = await createTransaction({ description: "Test", amount: { amount: 9223372036854775807n, currency: "USD" }, type: "CREDIT" });
    expect(result.amount.amount).toBe(9223372036854775807n);
    expect(JSON.parse(fetch.mock.calls[0][1].body).amount.amount).toBe("9223372036854775807");
    respond({ ...transaction, amount: { amount: 125, currency: "USD" } }, 201);
    await expect(createTransaction({ description: "Test", amount: { amount: 125n, currency: "USD" }, type: "CREDIT" })).rejects.toBeInstanceOf(ApiContractError);
  });

  it("rejects malformed list and detail shapes", async () => {
    respond({ items: [{ ...transaction, amount: { amount: 125, currency: "USD" } }] });
    await expect(getTransactions()).rejects.toBeInstanceOf(ApiContractError);
    respond({ ...transaction, status: "UNKNOWN" });
    await expect(getTransaction("tx-1")).rejects.toBeInstanceOf(ApiContractError);
  });

  it.each(["2026-99-99T00:00:00Z", "2026-02-30T00:00:00Z", "2026-09-02T24:00:00Z", "2025-02-29T12:00:00Z"])("rejects impossible instant %s", async (createdAt) => {
    respond({ ...transaction, createdAt });
    await expect(getTransaction("tx-1")).rejects.toBeInstanceOf(ApiContractError);
    respond({ items: [{ ...transaction, createdAt }] });
    await expect(getTransactions()).rejects.toBeInstanceOf(ApiContractError);
  });

  it.each(["2024-02-29T12:00:00Z", "2026-09-02T00:00:00.000000001Z"])("accepts valid instant %s", async (createdAt) => {
    respond({ ...transaction, createdAt });
    expect((await getTransaction("tx-1")).createdAt.getTime()).toBe(new Date(createdAt).getTime());
  });

  it("distinguishes server errors, malformed error bodies, and non-JSON success", async () => {
    respond({ code: "NOT_FOUND", message: "missing" }, 404);
    await expect(getTransaction("missing")).rejects.toMatchObject({ name: "ApiError", status: 404, code: "NOT_FOUND" });
    respond({ code: "OTHER", message: "unexpected" }, 500);
    await expect(getTransactions()).rejects.toBeInstanceOf(ApiContractError);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("not JSON")));
    await expect(getTransactions()).rejects.toBeInstanceOf(ApiContractError);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("proxy unavailable", { status: 502 })));
    await expect(getTransactions()).rejects.toBeInstanceOf(ApiError);
  });
});
