// Generic HTTP + contract handling. Every API call goes: fetch (NetworkError if it never completes) -> status check -> parse -> schema validate.

import type { ValidateFunction } from "ajv";
import { describeErrors, validateApiError } from "./schemas";

/**
 * `fetch` itself rejected: no response arrived (backend down, DNS, CORS, connection dropped). The
 * request may or may not have reached the server. This is the only place a fetch rejection is
 * classified, so a TypeError thrown by later code stays a bug report rather than a network report.
 */
export class NetworkError extends Error {
  constructor(
    readonly url: string,
    cause: unknown,
  ) {
    super(`Request to ${url} failed: ${cause instanceof Error ? cause.message : String(cause)}`, { cause });
    this.name = "NetworkError";
  }
}

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

/** The request body failed the JSON Schema contract before being sent; nothing reached the server. */
export class RequestContractError extends Error {
  constructor(
    readonly url: string,
    detail: string,
  ) {
    super(`Request to ${url} violates the API contract: ${detail}`);
    this.name = "RequestContractError";
  }
}

export async function getJson<TWire>(url: string, validate: ValidateFunction<TWire>): Promise<TWire> {
  const response = await send(url, { headers: { Accept: "application/json" } });
  return handleResponse(url, response, validate);
}

/** `headers` are request-specific additions (e.g. `Idempotency-Key`); the JSON media types are always set. */
export async function postJson<TWire>(url: string, body: unknown, validate: ValidateFunction<TWire>, headers: Record<string, string> = {}): Promise<TWire> {
  const response = await send(url, {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/json", ...headers },
    body: JSON.stringify(body),
  });
  return handleResponse(url, response, validate);
}

async function send(url: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(url, init);
  } catch (cause) {
    throw new NetworkError(url, cause);
  }
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
