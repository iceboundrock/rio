import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { Details } from "./CardTransactionDetailsPage";
import type { CardTransaction } from "../types/cardTransaction";

const cardTransaction: CardTransaction = {
  id: "tx-1",
  description: "Test",
  amount: { amount: 1250n, currency: "USD" },
  type: "CREDIT",
  status: "COMPLETED",
  createdAt: new Date("2026-09-02T15:30:00.123456789Z"),
  createdAtInstant: "2026-09-02T15:30:00.123456789Z",
};

describe("card transaction details view", () => {
  it("shows the exact API instant next to the localized date instead of the millisecond-truncated one", () => {
    const html = renderToStaticMarkup(<Details cardTransaction={cardTransaction} />);
    expect(html).toContain(cardTransaction.createdAt.toLocaleString());
    expect(html).toContain("(2026-09-02T15:30:00.123456789Z)");
    expect(html).not.toContain("2026-09-02T15:30:00.123Z");
  });

  it("shows a whole-second instant unchanged", () => {
    const wholeSecond = { ...cardTransaction, createdAt: new Date("2026-09-02T15:30:00Z"), createdAtInstant: "2026-09-02T15:30:00Z" };
    const html = renderToStaticMarkup(<Details cardTransaction={wholeSecond} />);
    expect(html).toContain("(2026-09-02T15:30:00Z)");
    expect(html).not.toContain(".000Z");
  });
});
