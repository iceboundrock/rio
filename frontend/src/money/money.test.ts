import { describe, expect, it } from "vitest";
import {
  CURRENCIES,
  CurrencyMismatchError,
  MAX_WIRE_AMOUNT,
  addMoney,
  compareMoney,
  formatMoney,
  formatSignedMoney,
  moneyFromDecimalString,
  moneyFromJson,
  moneyToDecimalString,
  moneyToJson,
  subtractMoney,
  type Money,
} from "./money";

const usd = (amount: bigint): Money => ({ amount, currency: "USD" });
const eur = (amount: bigint): Money => ({ amount, currency: "EUR" });
const jpy = (amount: bigint): Money => ({ amount, currency: "JPY" });

describe("currency metadata", () => {
  it("has the expected precision", () => {
    expect(CURRENCIES.USD.precision).toBe(2);
    expect(CURRENCIES.EUR.precision).toBe(2);
    expect(CURRENCIES.JPY.precision).toBe(0);
  });
});

describe("arithmetic", () => {
  it("adds, subtracts and compares same-currency values", () => {
    expect(addMoney(usd(100n), usd(200n))).toEqual(usd(300n));
    expect(subtractMoney(usd(300n), usd(100n))).toEqual(usd(200n));
    expect(compareMoney(usd(1n), usd(2n))).toBe(-1);
    expect(compareMoney(usd(2n), usd(1n))).toBe(1);
    expect(compareMoney(usd(2n), usd(2n))).toBe(0);
  });

  it("rejects currency mismatch", () => {
    expect(() => addMoney(usd(1n), eur(1n))).toThrow(CurrencyMismatchError);
    expect(() => subtractMoney(usd(1n), eur(1n))).toThrow(CurrencyMismatchError);
    expect(() => compareMoney(usd(1n), eur(1n))).toThrow(CurrencyMismatchError);
  });
});

describe("wire conversion", () => {
  it("parses a valid amount string into bigint", () => {
    expect(moneyFromJson({ amount: "1250", currency: "USD" })).toEqual(usd(1250n));
    expect(moneyFromJson({ amount: "-5", currency: "EUR" })).toEqual(eur(-5n));
  });

  it("keeps very large values exact", () => {
    const big = "123456789012345678901234567890";
    const money = moneyFromJson({ amount: big, currency: "USD" });
    expect(money.amount).toBe(123456789012345678901234567890n);
    expect(moneyToJson(money).amount).toBe(big);
  });

  it("rejects decimal, exponent and non-numeric strings", () => {
    expect(() => moneyFromJson({ amount: "12.34", currency: "USD" })).toThrow();
    expect(() => moneyFromJson({ amount: "1e3", currency: "USD" })).toThrow();
    expect(() => moneyFromJson({ amount: "", currency: "USD" })).toThrow();
    expect(() => moneyFromJson({ amount: "007", currency: "USD" })).toThrow();
  });

  it("rejects unsupported currencies", () => {
    expect(() => moneyFromJson({ amount: "1", currency: "GBP" as never })).toThrow();
  });
});

describe("moneyToDecimalString", () => {
  it("formats by currency precision", () => {
    expect(moneyToDecimalString(usd(1234n))).toBe("12.34");
    expect(moneyToDecimalString(usd(5n))).toBe("0.05");
    expect(moneyToDecimalString(usd(0n))).toBe("0.00");
    expect(moneyToDecimalString(usd(100n))).toBe("1.00");
    expect(moneyToDecimalString(jpy(1234n))).toBe("1234");
    expect(moneyToDecimalString(usd(-1234n))).toBe("-12.34");
    expect(moneyToDecimalString(usd(-5n))).toBe("-0.05");
  });

  it("is exact for values beyond Number precision", () => {
    expect(moneyToDecimalString(usd(1234567890123456789012n))).toBe("12345678901234567890.12");
  });
});

describe("formatMoney", () => {
  it("adds symbol and grouping", () => {
    expect(formatMoney(usd(120000n))).toBe("$1,200.00");
    expect(formatMoney(usd(1825n))).toBe("$18.25");
    expect(formatMoney(usd(5n))).toBe("$0.05");
    expect(formatMoney(jpy(1500n))).toBe("¥1,500");
    expect(formatMoney(eur(-99n))).toBe("-€0.99");
  });

  it("stays exact for huge values", () => {
    expect(formatMoney(usd(1234567890123456789012n))).toBe("$12,345,678,901,234,567,890.12");
  });

  it("signs by card transaction direction", () => {
    expect(formatSignedMoney(usd(500n), "DEBIT")).toBe("-$5.00");
    expect(formatSignedMoney(usd(500n), "CREDIT")).toBe("+$5.00");
    expect(formatSignedMoney(jpy(1500n), "CREDIT")).toBe("+¥1,500");
  });
});

describe("MAX_WIRE_AMOUNT", () => {
  it("is the signed 64-bit maximum the backend stores", () => {
    expect(MAX_WIRE_AMOUNT).toBe(2n ** 63n - 1n);
    expect(MAX_WIRE_AMOUNT.toString(10)).toBe("9223372036854775807");
  });
});

describe("moneyFromDecimalString", () => {
  it("parses major-unit text by currency precision", () => {
    expect(moneyFromDecimalString("12.50", "USD")).toEqual(usd(1250n));
    expect(moneyFromDecimalString("12.5", "USD")).toEqual(usd(1250n));
    expect(moneyFromDecimalString("12", "USD")).toEqual(usd(1200n));
    expect(moneyFromDecimalString(" 0.05 ", "USD")).toEqual(usd(5n));
    expect(moneyFromDecimalString("1250", "JPY")).toEqual(jpy(1250n));
  });

  it("stays exact beyond Number precision", () => {
    expect(moneyFromDecimalString("123456789012345678901234567890.12", "EUR")).toEqual(eur(12345678901234567890123456789012n));
  });

  it("returns null for too many decimals, non-positive, and non-numeric text", () => {
    for (const [text, currency] of [
      ["12.501", "USD"], ["12.5", "JPY"], ["0", "USD"], ["0.00", "USD"], ["-1", "USD"], ["1e3", "USD"],
      ["", "USD"], ["1 000", "USD"], ["$12", "USD"], [".5", "USD"], ["12.", "USD"], ["abc", "USD"],
    ] as const) {
      expect(moneyFromDecimalString(text, currency), `${text} ${currency}`).toBeNull();
    }
  });
});
