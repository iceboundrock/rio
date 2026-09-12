// Frontend Money: bigint minor units + currency. Never a JavaScript number.
// The backend is authoritative for financial calculations; this file only has what the UI needs.

export type CurrencyCode = "BRL" | "CAD" | "CNY" | "EUR" | "JPY" | "USD";

export interface CurrencyInfo {
  code: CurrencyCode;
  /** Decimal places in the major unit: USD 2 (12.50), JPY 0 (1250). */
  precision: number;
  symbol: string;
}

export const CURRENCIES: Record<CurrencyCode, CurrencyInfo> = {
  BRL: { code: "BRL", precision: 2, symbol: "R$" },
  CAD: { code: "CAD", precision: 2, symbol: "CA$" },
  CNY: { code: "CNY", precision: 2, symbol: "CN¥" },
  EUR: { code: "EUR", precision: 2, symbol: "€" },
  JPY: { code: "JPY", precision: 0, symbol: "¥" },
  USD: { code: "USD", precision: 2, symbol: "$" },
};

export function isCurrencyCode(value: string): value is CurrencyCode {
  return Object.prototype.hasOwnProperty.call(CURRENCIES, value);
}

export interface Money {
  amount: bigint;
  currency: CurrencyCode;
}

/** Wire shape from contracts/schemas/money.schema.json. Only src/api should touch this. */
export interface MoneyJson {
  amount: string;
  currency: CurrencyCode;
}

export class CurrencyMismatchError extends Error {
  constructor(a: CurrencyCode, b: CurrencyCode) {
    super(`Currency mismatch: ${a} vs ${b}`);
    this.name = "CurrencyMismatchError";
  }
}

function requireSameCurrency(a: Money, b: Money): void {
  if (a.currency !== b.currency) throw new CurrencyMismatchError(a.currency, b.currency);
}

export function addMoney(a: Money, b: Money): Money {
  requireSameCurrency(a, b);
  return { amount: a.amount + b.amount, currency: a.currency };
}

export function subtractMoney(a: Money, b: Money): Money {
  requireSameCurrency(a, b);
  return { amount: a.amount - b.amount, currency: a.currency };
}

export function compareMoney(a: Money, b: Money): -1 | 0 | 1 {
  requireSameCurrency(a, b);
  if (a.amount < b.amount) return -1;
  if (a.amount > b.amount) return 1;
  return 0;
}

// ---- wire conversion ----

const INTEGER_STRING = /^-?(0|[1-9][0-9]*)$/;

/**
 * Converts a schema-validated wire object to Money. The schema already guarantees the
 * string shape; the regex is a second line of defence before BigInt() is called.
 */
export function moneyFromJson(json: MoneyJson): Money {
  if (!INTEGER_STRING.test(json.amount)) {
    throw new Error(`Invalid money amount on the wire: "${json.amount}"`);
  }
  if (!isCurrencyCode(json.currency)) {
    throw new Error(`Unsupported currency on the wire: "${json.currency}"`);
  }
  return { amount: BigInt(json.amount), currency: json.currency };
}

export function moneyToJson(money: Money): MoneyJson {
  return { amount: money.amount.toString(10), currency: money.currency };
}

// ---- formatting (exact; no floating point anywhere) ----

/** Money(1234n, USD) -> "12.34"; Money(5n, USD) -> "0.05"; Money(1234n, JPY) -> "1234"; Money(-1234n, USD) -> "-12.34" */
export function moneyToDecimalString(money: Money): string {
  const { precision } = CURRENCIES[money.currency];
  const negative = money.amount < 0n;
  const digits = (negative ? -money.amount : money.amount).toString(10);
  if (precision === 0) return (negative ? "-" : "") + digits;

  const padded = digits.padStart(precision + 1, "0");
  const major = padded.slice(0, padded.length - precision);
  const minor = padded.slice(padded.length - precision);
  return `${negative ? "-" : ""}${major}.${minor}`;
}

/**
 * Human display with symbol and thousands grouping: "$1,200.00", "-¥1,500".
 * Intl.NumberFormat formats bigint exactly, so this stays correct for any magnitude.
 */
export function formatMoney(money: Money): string {
  const { precision, symbol } = CURRENCIES[money.currency];
  const negative = money.amount < 0n;
  const abs = negative ? -money.amount : money.amount;
  const scale = 10n ** BigInt(precision);
  const major = abs / scale;
  const minor = abs % scale;
  const grouped = new Intl.NumberFormat("en-US").format(major);
  const fraction = precision === 0 ? "" : "." + minor.toString(10).padStart(precision, "0");
  return `${negative ? "-" : ""}${symbol}${grouped}${fraction}`;
}

/** Transaction display: DEBIT shows as an outflow ("-$5.00"), CREDIT as an inflow ("+$5.00"). */
export function formatSignedMoney(money: Money, direction: "CREDIT" | "DEBIT"): string {
  return (direction === "DEBIT" ? "-" : "+") + formatMoney(money);
}
