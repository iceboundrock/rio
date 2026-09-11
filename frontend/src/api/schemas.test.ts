import { describe, expect, it } from "vitest";
import {
  validateApiError,
  validateCreateTransactionRequest,
  validateMoney,
  validateTransaction,
  validateTransactionListResponse,
} from "./schemas";

// These tests prove the frontend is using the real shared schemas and that contract drift fails loudly.

const validTransaction = {
  id: "seed-0002",
  description: "Blue Bottle Coffee",
  amount: { amount: "525", currency: "USD" },
  type: "DEBIT",
  status: "COMPLETED",
  createdAt: "2026-09-02T15:30:00Z",
};

describe("money schema", () => {
  it("accepts integer strings and supported currencies", () => {
    expect(validateMoney({ amount: "1250", currency: "USD" })).toBe(true);
    expect(validateMoney({ amount: "0", currency: "JPY" })).toBe(true);
    expect(validateMoney({ amount: "-100", currency: "EUR" })).toBe(true);
  });

  it("rejects decimals, exponents, numbers, symbols and unknown currencies", () => {
    expect(validateMoney({ amount: "12.34", currency: "USD" })).toBe(false);
    expect(validateMoney({ amount: "1e3", currency: "USD" })).toBe(false);
    expect(validateMoney({ amount: 1250, currency: "USD" })).toBe(false);
    expect(validateMoney({ amount: "$12", currency: "USD" })).toBe(false);
    expect(validateMoney({ amount: "1 000", currency: "USD" })).toBe(false);
    expect(validateMoney({ amount: "100", currency: "usd" })).toBe(false);
    expect(validateMoney({ amount: "100", currency: "GBP" })).toBe(false);
    expect(validateMoney({ amount: "100", currency: "USD", extra: 1 })).toBe(false);
  });
});

describe("transaction schemas", () => {
  it("accept the documented shapes", () => {
    expect(validateTransaction(validTransaction)).toBe(true);
    expect(validateTransactionListResponse({ items: [validTransaction] })).toBe(true);
    expect(validateTransactionListResponse({ items: [] })).toBe(true);
    expect(validateCreateTransactionRequest({ description: "Lunch", amount: { amount: "1800", currency: "USD" }, type: "DEBIT" })).toBe(true);
  });

  it("require positive magnitudes for transactions", () => {
    expect(validateTransaction({ ...validTransaction, amount: { amount: "0", currency: "USD" } })).toBe(false);
    expect(validateTransaction({ ...validTransaction, amount: { amount: "-525", currency: "USD" } })).toBe(false);
    expect(validateCreateTransactionRequest({ description: "x", amount: { amount: "-1", currency: "USD" }, type: "DEBIT" })).toBe(false);
  });

  it("reject drift: numeric amount, lowercase currency, unknown status, extra fields, missing fields", () => {
    expect(validateTransaction({ ...validTransaction, amount: { amount: 525, currency: "USD" } })).toBe(false);
    expect(validateTransaction({ ...validTransaction, amount: { amount: "525", currency: "usd" } })).toBe(false);
    expect(validateTransaction({ ...validTransaction, status: "REVERSED" })).toBe(false);
    expect(validateTransaction({ ...validTransaction, category: "Food" })).toBe(false);
    const { createdAt: _omitted, ...missingCreatedAt } = validTransaction;
    expect(validateTransaction(missingCreatedAt)).toBe(false);
    expect(validateTransactionListResponse({ items: [validTransaction], total: 1 })).toBe(false);
  });
});

describe("api error schema", () => {
  it("accepts known codes only", () => {
    expect(validateApiError({ code: "NOT_FOUND", message: "transaction x not found" })).toBe(true);
    expect(validateApiError({ code: "TEAPOT", message: "short and stout" })).toBe(false);
    expect(validateApiError({ code: "NOT_FOUND" })).toBe(false);
  });
});
