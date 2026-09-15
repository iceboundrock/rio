import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, getJson, NetworkError, ResponseBodyError } from "./client";
import { validateApiError } from "./schemas";
import { describeError } from "./errors";

afterEach(() => vi.unstubAllGlobals());

/** A body whose stream delivers `firstChunk` and then errors the way a loader does when the connection drops. */
function bodyThatDropsAfter(firstChunk: string): ReadableStream<Uint8Array> {
  return new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode(firstChunk));
      controller.error(new TypeError("terminated"));
    },
  });
}

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
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(bodyThatDropsAfter('{"items":['), { status: 200 })));
    const error = await getJson("/api/card-transactions", validateApiError).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ResponseBodyError);
    expect(error).not.toBeInstanceOf(NetworkError);
    expect((error as ResponseBodyError).status).toBe(200);
    expect((error as ResponseBodyError).url).toBe("/api/card-transactions");
    expect((error as ResponseBodyError).cause).toBeInstanceOf(TypeError);
    expect(describeError(error)).toBe("The connection to the server was lost while reading the response. Retry the request.");
  });

  it("reports a non-2xx response whose body fails mid-stream the same way, keeping the status", async () => {
    // The server's error body never arrived, so there is nothing better to show than the retry message:
    // retrying is how the user gets to see what the server actually said. The status stays on the error.
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(bodyThatDropsAfter('{"code":"'), { status: 500 })));
    const error = await getJson("/api/card-transactions", validateApiError).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ResponseBodyError);
    expect(error).not.toBeInstanceOf(ApiError);
    expect((error as ResponseBodyError).status).toBe(500);
    expect(describeError(error)).toBe("The connection to the server was lost while reading the response. Retry the request.");
  });

  it("shows any other TypeError as itself rather than blaming the network", () => {
    // A bug after the response arrived (e.g. a broken validator import) must not look like an outage.
    expect(describeError(new TypeError("func1 is not a function"))).toBe("func1 is not a function");
  });
});
