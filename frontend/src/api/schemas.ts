// Runtime validators for the shared JSON Schemas (contracts/schemas), plus the wire types.
// The validators are precompiled by scripts/generate-validators.mjs into validators.generated.js so the
// browser never runs Ajv's compiler (which needs `new Function`, forbidden by a strict CSP); this module
// only gives them their wire types. Wire types describe the JSON exactly as it travels;
// src/api/cardTransactions.ts maps them to domain types.

import type { ValidateFunction } from "ajv";
import * as generated from "./validators.generated.js";
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

export const validateMoney = generated.validateMoney as ValidateFunction<MoneyJson>;
export const validateCardTransaction = generated.validateCardTransaction as ValidateFunction<CardTransactionJson>;
export const validateCardTransactionListResponse = generated.validateCardTransactionListResponse as ValidateFunction<CardTransactionListResponseJson>;
export const validateCreateCardTransactionRequest = generated.validateCreateCardTransactionRequest as ValidateFunction<CreateCardTransactionRequestJson>;
export const validateCreateCardTransactionsRequest = generated.validateCreateCardTransactionsRequest as ValidateFunction<CreateCardTransactionsRequestJson>;
export const validateApiError = generated.validateApiError as ValidateFunction<ApiErrorJson>;

/** Human-readable summary of why a validator rejected a value. */
export function describeErrors(validate: ValidateFunction): string {
  return (validate.errors ?? [])
    .map((e) => `${e.instancePath || "(root)"} ${e.message ?? "is invalid"}`)
    .join("; ");
}
