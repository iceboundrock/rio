// Card transaction endpoints. Pages call these and receive domain objects (bigint Money, Date timestamps plus the verbatim createdAt instant).

import { ApiContractError, RequestContractError, getJson, postJson } from "./client";
import {
  describeErrors,
  validateCreateCardTransactionRequest,
  validateCreateCardTransactionsRequest,
  validateCardTransaction,
  validateCardTransactionListResponse,
  type CreateCardTransactionRequestJson,
  type CardTransactionJson,
} from "./schemas";
import { moneyFromJson, moneyToJson } from "../money/money";
import type { CreateCardTransactionInput, CardTransaction } from "../types/cardTransaction";

function fromJson(json: CardTransactionJson, url: string): CardTransaction {
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
    createdAtInstant: json.createdAt,
  };
}

export async function getCardTransactions(): Promise<CardTransaction[]> {
  const response = await getJson("/api/card-transactions", validateCardTransactionListResponse);
  return response.items.map((item) => fromJson(item, "/api/card-transactions"));
}

export async function getCardTransaction(id: string): Promise<CardTransaction> {
  const url = `/api/card-transactions/${encodeURIComponent(id)}`;
  const json = await getJson(url, validateCardTransaction);
  return fromJson(json, url);
}

function toJson(input: CreateCardTransactionInput): CreateCardTransactionRequestJson {
  return { description: input.description, amount: moneyToJson(input.amount), type: input.type };
}

export async function createCardTransaction(input: CreateCardTransactionInput): Promise<CardTransaction> {
  const body = toJson(input);
  if (!validateCreateCardTransactionRequest(body)) {
    throw new RequestContractError("/api/card-transactions", describeErrors(validateCreateCardTransactionRequest));
  }
  const json = await postJson("/api/card-transactions", body, validateCardTransaction);
  return fromJson(json, "/api/card-transactions");
}

/** Creates every input or none: the server inserts an array body in one transaction. */
export async function createCardTransactions(inputs: CreateCardTransactionInput[]): Promise<CardTransaction[]> {
  const body = inputs.map(toJson);
  if (!validateCreateCardTransactionsRequest(body)) {
    throw new RequestContractError("/api/card-transactions", describeErrors(validateCreateCardTransactionsRequest));
  }
  const response = await postJson("/api/card-transactions", body, validateCardTransactionListResponse);
  return response.items.map((item) => fromJson(item, "/api/card-transactions"));
}
