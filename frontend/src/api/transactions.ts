// Transaction endpoints. Pages call these and receive domain objects (bigint Money, Date timestamps).

import { getJson, postJson } from "./client";
import {
  validateCreateTransactionRequest,
  validateTransaction,
  validateTransactionListResponse,
  type CreateTransactionRequestJson,
  type TransactionJson,
} from "./schemas";
import { moneyFromJson, moneyToJson } from "../money/money";
import type { CreateTransactionInput, Transaction } from "../types/transaction";

function fromJson(json: TransactionJson): Transaction {
  return {
    id: json.id,
    description: json.description,
    amount: moneyFromJson(json.amount),
    type: json.type,
    status: json.status,
    createdAt: new Date(json.createdAt),
  };
}

export async function getTransactions(): Promise<Transaction[]> {
  const response = await getJson("/api/transactions", validateTransactionListResponse);
  return response.items.map(fromJson);
}

export async function getTransaction(id: string): Promise<Transaction> {
  const json = await getJson(`/api/transactions/${encodeURIComponent(id)}`, validateTransaction);
  return fromJson(json);
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
  return fromJson(json);
}
