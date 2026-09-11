import { useEffect, useState } from "react";
import { getTransactions } from "../api/transactions";
import { describeError } from "../api/errors";
import TransactionList from "../components/TransactionList";
import type { Transaction } from "../types/transaction";

type State =
  | { kind: "loading" }
  | { kind: "error"; message: string }
  | { kind: "loaded"; transactions: Transaction[] };

export default function TransactionListPage() {
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    let cancelled = false;
    getTransactions()
      .then((transactions) => !cancelled && setState({ kind: "loaded", transactions }))
      .catch((error: unknown) => !cancelled && setState({ kind: "error", message: describeError(error) }));
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <>
      <header className="page-header">
        <h1>Transactions</h1>
        {state.kind === "loaded" && <p className="muted">{state.transactions.length} transactions</p>}
      </header>

      {state.kind === "loading" && <p className="state">Loading…</p>}
      {state.kind === "error" && <p className="state state-error">{state.message}</p>}
      {state.kind === "loaded" && state.transactions.length === 0 && <p className="state">No transactions yet.</p>}
      {state.kind === "loaded" && state.transactions.length > 0 && <TransactionList transactions={state.transactions} />}
    </>
  );
}
