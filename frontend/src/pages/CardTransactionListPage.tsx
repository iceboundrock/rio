import { useCallback, useEffect, useState } from "react";
import { getCardTransactions } from "../api/cardTransactions";
import { describeError } from "../api/errors";
import CardTransactionList from "../components/CardTransactionList";
import CreateCardTransactionsForm from "../components/CreateCardTransactionsForm";
import type { CardTransaction } from "../types/cardTransaction";

type State =
  | { kind: "loading" }
  | { kind: "error"; message: string }
  | { kind: "loaded"; cardTransactions: CardTransaction[] };

export default function CardTransactionListPage() {
  const [state, setState] = useState<State>({ kind: "loading" });

  // Re-fetch rather than merge what the form returns: the server assigns createdAt and the order.
  const load = useCallback(() => {
    let cancelled = false;
    getCardTransactions()
      .then((cardTransactions) => !cancelled && setState({ kind: "loaded", cardTransactions }))
      .catch((error: unknown) => !cancelled && setState({ kind: "error", message: describeError(error) }));
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(load, [load]);

  return (
    <>
      <header className="page-header">
        <h1>Card Transactions</h1>
        {state.kind === "loaded" && <p className="muted">{state.cardTransactions.length} card transactions</p>}
      </header>

      <CreateCardTransactionsForm onCreated={load} />

      {state.kind === "loading" && <p className="state">Loading…</p>}
      {state.kind === "error" && <p className="state state-error">{state.message}</p>}
      {state.kind === "loaded" && state.cardTransactions.length === 0 && <p className="state">No card transactions yet.</p>}
      {state.kind === "loaded" && state.cardTransactions.length > 0 && <CardTransactionList cardTransactions={state.cardTransactions} />}
    </>
  );
}
