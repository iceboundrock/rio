import type { Money } from "../money/money";

// Domain types used by pages and components. Money is already converted to bigint here.

export type TransactionType = "CREDIT" | "DEBIT";

export type TransactionStatus = "PENDING" | "COMPLETED" | "DECLINED";

export interface Transaction {
  id: string;
  description: string;
  /** Magnitude, always > 0. Direction comes from `type`. */
  amount: Money;
  type: TransactionType;
  status: TransactionStatus;
  createdAt: Date;
  /** The validated API instant, verbatim (e.g. `2026-09-02T15:30:00.123456789Z`). Date truncates to milliseconds; use this for exact display and log correlation. */
  createdAtInstant: string;
}

export interface CreateTransactionInput {
  description: string;
  amount: Money;
  type: TransactionType;
}
