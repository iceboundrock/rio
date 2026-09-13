import { describe, expect, it } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { Details } from "./TransactionDetailsPage";
import type { Transaction } from "../types/transaction";

const transaction: Transaction = {
  id: "tx-1",
  description: "Test",
  amount: { amount: 1250n, currency: "USD" },
  type: "CREDIT",
  status: "COMPLETED",
  createdAt: new Date("2026-09-02T15:30:00.123456789Z"),
  createdAtInstant: "2026-09-02T15:30:00.123456789Z",
};

describe("transaction details view", () => {
  it("shows the exact API instant next to the localized date instead of the millisecond-truncated one", () => {
    const html = renderToStaticMarkup(<Details transaction={transaction} />);
    expect(html).toContain(transaction.createdAt.toLocaleString());
    expect(html).toContain("(2026-09-02T15:30:00.123456789Z)");
    expect(html).not.toContain("2026-09-02T15:30:00.123Z");
  });

  it("shows a whole-second instant unchanged", () => {
    const wholeSecond = { ...transaction, createdAt: new Date("2026-09-02T15:30:00Z"), createdAtInstant: "2026-09-02T15:30:00Z" };
    const html = renderToStaticMarkup(<Details transaction={wholeSecond} />);
    expect(html).toContain("(2026-09-02T15:30:00Z)");
    expect(html).not.toContain(".000Z");
  });
});
