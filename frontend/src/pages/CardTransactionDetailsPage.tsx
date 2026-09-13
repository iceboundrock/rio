import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { ApiError } from "../api/client";
import { getCardTransaction } from "../api/cardTransactions";
import { describeError } from "../api/errors";
import { formatSignedMoney, moneyToDecimalString } from "../money/money";
import type { CardTransaction } from "../types/cardTransaction";

type State =
  | { kind: "loading" }
  | { kind: "not-found" }
  | { kind: "error"; message: string }
  | { kind: "loaded"; cardTransaction: CardTransaction };

export default function CardTransactionDetailsPage() {
  const { id = "" } = useParams();
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    let cancelled = false;
    setState({ kind: "loading" });
    getCardTransaction(id)
      .then((cardTransaction) => !cancelled && setState({ kind: "loaded", cardTransaction }))
      .catch((error: unknown) => {
        if (cancelled) return;
        if (error instanceof ApiError && error.status === 404) setState({ kind: "not-found" });
        else setState({ kind: "error", message: describeError(error) });
      });
    return () => {
      cancelled = true;
    };
  }, [id]);

  return (
    <>
      <p>
        <Link to="/card-transactions">← Back to Card Transactions</Link>
      </p>

      {state.kind === "loading" && <p className="state">Loading…</p>}
      {state.kind === "not-found" && (
        <p className="state state-error">
          No card transaction with ID <code>{id}</code>.
        </p>
      )}
      {state.kind === "error" && <p className="state state-error">{state.message}</p>}
      {state.kind === "loaded" && <Details cardTransaction={state.cardTransaction} />}
    </>
  );
}

export function Details({ cardTransaction }: { cardTransaction: CardTransaction }) {
  const { amount } = cardTransaction;
  return (
    <article className="details">
      <h1>{cardTransaction.description}</h1>
      <p className={`amount amount-${cardTransaction.type.toLowerCase()}`}>{formatSignedMoney(amount, cardTransaction.type)}</p>
      <dl>
        <dt>Card Transaction ID</dt>
        <dd>
          <code>{cardTransaction.id}</code>
        </dd>
        <dt>Amount</dt>
        <dd>
          {moneyToDecimalString(amount)} {amount.currency}
        </dd>
        <dt>Currency</dt>
        <dd>{amount.currency}</dd>
        <dt>Type</dt>
        <dd>{cardTransaction.type}</dd>
        <dt>Status</dt>
        <dd>
          <span className={`badge badge-${cardTransaction.status.toLowerCase()}`}>{cardTransaction.status}</span>
        </dd>
        <dt>Created</dt>
        <dd>
          {cardTransaction.createdAt.toLocaleString()} <span className="muted">({cardTransaction.createdAtInstant})</span>
        </dd>
      </dl>
    </article>
  );
}
