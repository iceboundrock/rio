/**
 * Delivers only the most recently started request. A page that re-fetches (initial load, then a
 * refresh after a create) can otherwise have the slower earlier response overwrite the newer state.
 */
export interface LatestRequest {
  /** Starts a request; its result or error is dropped if a newer `run` or `cancel` happens first. */
  run<T>(start: () => Promise<T>, onResult: (value: T) => void, onError: (error: unknown) => void): void;
  /** Drops every in-flight request, e.g. on unmount. */
  cancel(): void;
}

export function latestRequest(): LatestRequest {
  let latest = 0;
  return {
    run(start, onResult, onError) {
      const id = ++latest;
      start().then(
        (value) => id === latest && onResult(value),
        (error: unknown) => id === latest && onError(error),
      );
    },
    cancel() {
      latest++;
    },
  };
}
