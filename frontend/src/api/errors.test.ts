import { afterEach, describe, expect, it, vi } from "vitest";
import { getJson, NetworkError } from "./client";
import { validateApiError } from "./schemas";
import { describeError } from "./errors";

afterEach(() => vi.unstubAllGlobals());

describe("describeError", () => {
  it("reports a failed fetch as an unreachable server", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));
    const error = await getJson("/api/card-transactions", validateApiError).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(NetworkError);
    expect(describeError(error)).toBe("Could not reach the server. Is the backend running on port 8080?");
  });

  it("shows any other TypeError as itself rather than blaming the network", () => {
    // A bug after the response arrived (e.g. a broken validator import) must not look like an outage.
    expect(describeError(new TypeError("func1 is not a function"))).toBe("func1 is not a function");
  });
});
