import { afterEach, describe, expect, it, vi } from "vitest";
import { getJson, NetworkError, ResponseBodyError } from "./client";
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

  it("reports a body that fails mid-stream as a lost connection, not an unreachable server", async () => {
    // The server answered with headers, then the connection dropped: the loader rejects `response.text()`
    // with its own TypeError ("Failed to fetch", "NetworkError when attempting to fetch resource.", "terminated").
    const body = new ReadableStream({
      start(controller) {
        controller.enqueue(new TextEncoder().encode('{"items":['));
        controller.error(new TypeError("terminated"));
      },
    });
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(body, { status: 200 })));
    const error = await getJson("/api/card-transactions", validateApiError).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ResponseBodyError);
    expect(error).not.toBeInstanceOf(NetworkError);
    expect((error as ResponseBodyError).status).toBe(200);
    expect(describeError(error)).toBe("The connection to the server was lost while reading the response. Retry the request.");
  });

  it("shows any other TypeError as itself rather than blaming the network", () => {
    // A bug after the response arrived (e.g. a broken validator import) must not look like an outage.
    expect(describeError(new TypeError("func1 is not a function"))).toBe("func1 is not a function");
  });
});
