import { describe, expect, it, vi } from "vitest";
import { latestRequest } from "./latestRequest";

/** A promise settled by the test, so response order can be chosen explicitly. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const settled = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

describe("latestRequest", () => {
  it("drops an earlier response that arrives after a later one (initial GET slower than the post-create refresh)", async () => {
    const onResult = vi.fn<(value: string) => void>();
    const onError = vi.fn();
    const requests = latestRequest();
    const initial = deferred<string>();
    const refresh = deferred<string>();

    requests.run(() => initial.promise, onResult, onError);
    requests.run(() => refresh.promise, onResult, onError);
    refresh.resolve("list with the new rows");
    initial.resolve("stale list");
    await settled();

    expect(onResult.mock.calls).toEqual([["list with the new rows"]]);
    expect(onError).not.toHaveBeenCalled();
  });

  it("drops an earlier failure that arrives after a later success", async () => {
    const onResult = vi.fn<(value: string) => void>();
    const onError = vi.fn();
    const requests = latestRequest();
    const initial = deferred<string>();
    const refresh = deferred<string>();

    requests.run(() => initial.promise, onResult, onError);
    requests.run(() => refresh.promise, onResult, onError);
    refresh.resolve("fresh");
    initial.reject(new Error("network"));
    await settled();

    expect(onResult.mock.calls).toEqual([["fresh"]]);
    expect(onError).not.toHaveBeenCalled();
  });

  it("delivers the latest request's result and error normally", async () => {
    const onResult = vi.fn<(value: string) => void>();
    const onError = vi.fn();
    const requests = latestRequest();

    requests.run(() => Promise.resolve("first"), onResult, onError);
    await settled();
    requests.run(() => Promise.reject(new Error("boom")), onResult, onError);
    await settled();

    expect(onResult.mock.calls).toEqual([["first"]]);
    expect(onError).toHaveBeenCalledTimes(1);
    expect((onError.mock.calls[0][0] as Error).message).toBe("boom");
  });

  it("delivers nothing after cancel, and a run after cancel works again", async () => {
    const onResult = vi.fn<(value: string) => void>();
    const onError = vi.fn();
    const requests = latestRequest();
    const inFlight = deferred<string>();

    requests.run(() => inFlight.promise, onResult, onError);
    requests.cancel();
    inFlight.resolve("after unmount");
    await settled();
    expect(onResult).not.toHaveBeenCalled();

    requests.run(() => Promise.resolve("remounted"), onResult, onError);
    await settled();
    expect(onResult.mock.calls).toEqual([["remounted"]]);
  });
});
