// Transaction endpoints. Pages call these and receive domain objects (bigint Money, Date timestamps).

import { ApiContractError, getJson, postJson } from "./client";
import {
  validateCreateTransactionRequest,
  validateTransaction,
  validateTransactionListResponse,
  type CreateTransactionRequestJson,
  type TransactionJson,
} from "./schemas";
import { moneyFromJson, moneyToJson } from "../money/money";
import type { CreateTransactionInput, Transaction } from "../types/transaction";

function fromJson(json: TransactionJson, url: string): Transaction {
  const createdAt = new Date(json.createdAt);
  // Date can normalize impossible dates (e.g. February 30). Compare the calendar
  // fields too; retain support for Instant's sub-millisecond fractional seconds.
  if (Number.isNaN(createdAt.getTime()) || createdAt.toISOString().slice(0, 19) !== json.createdAt.slice(0, 19)) {
    throw new ApiContractError(url, `createdAt is not a valid UTC instant: ${json.createdAt}`);
  }
  return {
    id: json.id,
    description: json.description,
    amount: moneyFromJson(json.amount),
    type: json.type,
    status: json.status,
    createdAt,
  };
}

export async function getTransactions(): Promise<Transaction[]> {
  const response = await getJson("/api/transactions", validateTransactionListResponse);
  return response.items.map((item) => fromJson(item, "/api/transactions"));
}

export async function getTransaction(id: string): Promise<Transaction> {
  const url = `/api/transactions/${encodeURIComponent(id)}`;
  const json = await getJson(url, validateTransaction);
  return fromJson(json, url);
}

export async function createTransaction(input: CreateTransactionInput): Promise<Transaction> {
  const body: CreateTransactionRequestJson = {
    description: input.description,
    amount: moneyToJson(input.amount),
    type: input.type,
  };
  if (!validateCreateTransactionRequest(body)) {
    throw new Error(`Refusing to send a request that violates the contract: ${JSON.stringify(body)}`);
  }
  const json = await postJson("/api/transactions", body, validateTransaction);
  return fromJson(json, "/api/transactions");
}
