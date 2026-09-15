import { ApiContractError, ApiError, NetworkError, RequestContractError } from "./client";

/** Turns any thrown value into a message a page can show. */
export function describeError(error: unknown): string {
  if (error instanceof ApiError) return `Request failed (${error.status} ${error.code}): ${error.message}`;
  if (error instanceof ApiContractError) return `Unexpected response from the server. ${error.message}`;
  if (error instanceof RequestContractError) return `The request was not sent. ${error.message}`;
  if (error instanceof NetworkError) return "Could not reach the server. Is the backend running on port 8080?";
  if (error instanceof Error) return error.message;
  return String(error);
}
