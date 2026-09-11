// Compiles the shared JSON Schemas (contracts/schemas) into runtime validators.
// Wire types below describe the JSON exactly as it travels; src/api/transactions.ts maps them to domain types.

import Ajv2020, { type ValidateFunction } from "ajv/dist/2020";
import moneySchema from "@schemas/money.schema.json";
import transactionSchema from "@schemas/transaction.schema.json";
import transactionListResponseSchema from "@schemas/transaction-list-response.schema.json";
import createTransactionRequestSchema from "@schemas/create-transaction-request.schema.json";
import apiErrorSchema from "@schemas/api-error.schema.json";
import type { MoneyJson } from "../money/money";
import type { TransactionStatus, TransactionType } from "../types/transaction";

export interface TransactionJson {
  id: string;
  description: string;
  amount: MoneyJson;
  type: TransactionType;
  status: TransactionStatus;
  createdAt: string;
}

export interface TransactionListResponseJson {
  items: TransactionJson[];
}

export interface CreateTransactionRequestJson {
  description: string;
  amount: MoneyJson;
  type: TransactionType;
}

export interface ApiErrorJson {
  code: "VALIDATION_ERROR" | "NOT_FOUND" | "INTERNAL_ERROR";
  message: string;
}

const ajv = new Ajv2020({ allErrors: true, strict: true });
// money.schema.json is referenced by $id from the other schemas, so it must be registered.
ajv.addSchema(moneySchema);

export const validateMoney = ajv.compile<MoneyJson>(moneySchema);
export const validateTransaction = ajv.compile<TransactionJson>(transactionSchema);
export const validateTransactionListResponse = ajv.compile<TransactionListResponseJson>(transactionListResponseSchema);
export const validateCreateTransactionRequest = ajv.compile<CreateTransactionRequestJson>(createTransactionRequestSchema);
export const validateApiError = ajv.compile<ApiErrorJson>(apiErrorSchema);

/** Human-readable summary of why a validator rejected a value. */
export function describeErrors(validate: ValidateFunction): string {
  return (validate.errors ?? [])
    .map((e) => `${e.instancePath || "(root)"} ${e.message ?? "is invalid"}`)
    .join("; ");
}
