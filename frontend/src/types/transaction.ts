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
}

export interface CreateTransactionInput {
  description: string;
  amount: Money;
  type: TransactionType;
}
