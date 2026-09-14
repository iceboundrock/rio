// Compiles the shared JSON Schemas (contracts/schemas) into runtime validators.
// Wire types below describe the JSON exactly as it travels; src/api/cardTransactions.ts maps them to domain types.

import Ajv2020, { type ValidateFunction } from "ajv/dist/2020";
import moneySchema from "@schemas/money.schema.json";
import cardTransactionSchema from "@schemas/card-transaction.schema.json";
import cardTransactionListResponseSchema from "@schemas/card-transaction-list-response.schema.json";
import createCardTransactionRequestSchema from "@schemas/create-card-transaction-request.schema.json";
import createCardTransactionsRequestSchema from "@schemas/create-card-transactions-request.schema.json";
import apiErrorSchema from "@schemas/api-error.schema.json";
import type { MoneyJson } from "../money/money";
import type { CardTransactionStatus, CardTransactionType } from "../types/cardTransaction";

export interface CardTransactionJson {
  id: string;
  description: string;
  amount: MoneyJson;
  type: CardTransactionType;
  status: CardTransactionStatus;
  createdAt: string;
}

export interface CardTransactionListResponseJson {
  items: CardTransactionJson[];
}

export interface CreateCardTransactionRequestJson {
  description: string;
  amount: MoneyJson;
  type: CardTransactionType;
}

/** POST body: one request, or a non-empty array created all-or-nothing. */
export type CreateCardTransactionsRequestJson = CreateCardTransactionRequestJson | CreateCardTransactionRequestJson[];

export interface ApiErrorJson {
  code: "VALIDATION_ERROR" | "NOT_FOUND" | "INTERNAL_ERROR" | "IDEMPOTENCY_CONFLICT";
  message: string;
}

const ajv = new Ajv2020({ allErrors: true, strict: true });
// money.schema.json is referenced by $id from the other schemas, so it must be registered.
ajv.addSchema(moneySchema);

export const validateMoney = ajv.compile<MoneyJson>(moneySchema);
export const validateCardTransaction = ajv.compile<CardTransactionJson>(cardTransactionSchema);
export const validateCardTransactionListResponse = ajv.compile<CardTransactionListResponseJson>(cardTransactionListResponseSchema);
export const validateCreateCardTransactionRequest = ajv.compile<CreateCardTransactionRequestJson>(createCardTransactionRequestSchema);
// $ref's the single-request schema by $id, which compile() above registered; keep this after it.
export const validateCreateCardTransactionsRequest = ajv.compile<CreateCardTransactionsRequestJson>(createCardTransactionsRequestSchema);
export const validateApiError = ajv.compile<ApiErrorJson>(apiErrorSchema);

/** Human-readable summary of why a validator rejected a value. */
export function describeErrors(validate: ValidateFunction): string {
  return (validate.errors ?? [])
    .map((e) => `${e.instancePath || "(root)"} ${e.message ?? "is invalid"}`)
    .join("; ");
}
