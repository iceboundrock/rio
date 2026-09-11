// Generic HTTP + contract handling. Every API call goes: fetch -> status check -> parse -> schema validate.

import type { ValidateFunction } from "ajv";
import { describeErrors, validateApiError } from "./schemas";

/** The server answered with a non-2xx status and (normally) the shared ApiError body. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

/** The response did not match the JSON Schema contract (or was not JSON at all). */
export class ApiContractError extends Error {
  constructor(
    readonly url: string,
    detail: string,
  ) {
    super(`Response from ${url} violates the API contract: ${detail}`);
    this.name = "ApiContractError";
  }
}

export async function getJson<TWire>(url: string, validate: ValidateFunction<TWire>): Promise<TWire> {
  const response = await fetch(url, { headers: { Accept: "application/json" } });
  return handleResponse(url, response, validate);
}

export async function postJson<TWire>(url: string, body: unknown, validate: ValidateFunction<TWire>): Promise<TWire> {
  const response = await fetch(url, {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  return handleResponse(url, response, validate);
}

async function handleResponse<TWire>(url: string, response: Response, validate: ValidateFunction<TWire>): Promise<TWire> {
  const text = await response.text();
  let json: unknown;
  try {
    json = JSON.parse(text);
  } catch {
    if (!response.ok) throw new ApiError(response.status, "HTTP_ERROR", `HTTP ${response.status} from ${url}`);
    throw new ApiContractError(url, "body is not valid JSON");
  }

  if (!response.ok) {
    if (validateApiError(json)) throw new ApiError(response.status, json.code, json.message);
    throw new ApiContractError(url, `HTTP ${response.status} with unexpected error body: ${describeErrors(validateApiError)}`);
  }

  if (!validate(json)) throw new ApiContractError(url, describeErrors(validate));
  return json;
}
