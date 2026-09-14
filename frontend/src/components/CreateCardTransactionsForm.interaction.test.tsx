// @vitest-environment jsdom
// Drives the mounted, stateful form through real DOM events against a stubbed `fetch`. The pure view
// and the extracted helpers are covered without a DOM in CreateCardTransactionsForm.test.tsx.
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import CreateCardTransactionsForm from "./CreateCardTransactionsForm";

const createdLunch = { id: "tx-1", description: "Lunch", amount: { amount: "1800", currency: "USD" }, type: "DEBIT", status: "COMPLETED", createdAt: "2026-09-02T15:30:00Z" };

const jsonResponse = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });
const created = () => jsonResponse(201, { items: [createdLunch] });
// The server may have committed; the response never arrived.
const lostResponse = () => new TypeError("Failed to fetch");

const field = (label: string) => screen.getByLabelText(label) as HTMLInputElement;
const type = (label: string, value: string) => fireEvent.change(field(label), { target: { value } });
const submitButton = () => screen.getByRole("button", { name: /^(Create|Saving…)/ }) as HTMLButtonElement;
const clickCreate = () => fireEvent.click(screen.getByRole("button", { name: "Create" }));
const idempotencyKeyOf = (call: unknown[]) => ((call[1] as RequestInit).headers as Record<string, string>)["Idempotency-Key"];

function mount(fetch: ReturnType<typeof vi.fn>) {
  vi.stubGlobal("fetch", fetch);
  const onCreated = vi.fn();
  render(<CreateCardTransactionsForm onCreated={onCreated} />);
  type("Description 1", "Lunch");
  type("Amount 1", "18.00");
  return { onCreated };
}

describe("create card transactions form (mounted)", () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("retries an unchanged draft with the same Idempotency-Key and clears the form once the create succeeds", async () => {
    const fetch = vi.fn().mockRejectedValueOnce(lostResponse()).mockResolvedValueOnce(created());
    const { onCreated } = mount(fetch);

    clickCreate();
    await screen.findByText(/Could not reach the server/);
    expect(onCreated).not.toHaveBeenCalled();
    expect(field("Description 1").value).toBe("Lunch"); // the draft survives a failed attempt

    clickCreate();
    await waitFor(() => expect(onCreated).toHaveBeenCalledTimes(1));

    const keys = fetch.mock.calls.map(idempotencyKeyOf);
    expect(keys).toHaveLength(2);
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/);
    expect(keys[1]).toBe(keys[0]);
    expect(field("Description 1").value).toBe("");
    expect(field("Amount 1").value).toBe("");
    expect(screen.queryByText(/Could not reach the server/)).toBeNull();
  });

  it("keeps the key across a cosmetic edit and mints a new one when the request changes", async () => {
    const fetch = vi.fn().mockRejectedValue(lostResponse());
    mount(fetch);

    clickCreate();
    await screen.findByText(/Could not reach the server/);

    type("Description 1", " Lunch "); // trims to the same request
    clickCreate();
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(2));

    type("Amount 1", "18.01"); // a different request
    clickCreate();
    await waitFor(() => expect(fetch).toHaveBeenCalledTimes(3));

    const keys = fetch.mock.calls.map(idempotencyKeyOf);
    expect(keys[1]).toBe(keys[0]);
    expect(keys[2]).not.toBe(keys[0]);
  });

  it("disables the form while a request is in flight so a second click cannot start a second create", async () => {
    let respond!: (response: Response) => void;
    const fetch = vi.fn(() => new Promise<Response>((resolve) => (respond = resolve)));
    const { onCreated } = mount(fetch);

    clickCreate();
    const saving = await screen.findByRole("button", { name: "Saving…" });
    expect((saving as HTMLButtonElement).disabled).toBe(true);
    expect(field("Description 1").disabled).toBe(true);

    // A click on the disabled button is the only path a user has to a second submit: every control is
    // disabled, so there is nothing focused for Enter to submit through.
    fireEvent.click(saving);
    expect(fetch).toHaveBeenCalledTimes(1);

    respond(created());
    await waitFor(() => expect(onCreated).toHaveBeenCalledTimes(1));
    expect(submitButton().textContent).toBe("Create");
    expect(submitButton().disabled).toBe(false);
  });

  it("shows a 422 IDEMPOTENCY_CONFLICT body and keeps the draft", async () => {
    const message = "Idempotency-Key was already used with a different request";
    const fetch = vi.fn().mockResolvedValue(jsonResponse(422, { code: "IDEMPOTENCY_CONFLICT", message }));
    const { onCreated } = mount(fetch);

    clickCreate();
    await screen.findByText(`Request failed (422 IDEMPOTENCY_CONFLICT): ${message}`);

    expect(onCreated).not.toHaveBeenCalled();
    expect(field("Description 1").value).toBe("Lunch");
    expect(submitButton().disabled).toBe(false);
  });
});
