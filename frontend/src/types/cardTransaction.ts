import type { Money } from "../money/money";

// Domain types used by pages and components. Money is already converted to bigint here.

export type CardTransactionType = "CREDIT" | "DEBIT";

export type CardTransactionStatus = "PENDING" | "COMPLETED" | "DECLINED";

export interface CardTransaction {
  id: string;
  description: string;
  /** Magnitude, always > 0. Direction comes from `type`. */
  amount: Money;
  type: CardTransactionType;
  status: CardTransactionStatus;
  createdAt: Date;
  /** The validated API instant, verbatim (e.g. `2026-09-02T15:30:00.123456789Z`). Date truncates to milliseconds; use this for exact display and log correlation. */
  createdAtInstant: string;
}

export interface CreateCardTransactionInput {
  description: string;
  amount: Money;
  type: CardTransactionType;
}
