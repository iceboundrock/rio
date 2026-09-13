import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { ApiError } from "../api/client";
import { getTransaction } from "../api/transactions";
import { describeError } from "../api/errors";
import { formatSignedMoney, moneyToDecimalString } from "../money/money";
import type { Transaction } from "../types/transaction";

type State =
  | { kind: "loading" }
  | { kind: "not-found" }
  | { kind: "error"; message: string }
  | { kind: "loaded"; transaction: Transaction };

export default function TransactionDetailsPage() {
  const { id = "" } = useParams();
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    let cancelled = false;
    setState({ kind: "loading" });
    getTransaction(id)
      .then((transaction) => !cancelled && setState({ kind: "loaded", transaction }))
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
        <Link to="/transactions">← Back to Transactions</Link>
      </p>

      {state.kind === "loading" && <p className="state">Loading…</p>}
      {state.kind === "not-found" && (
        <p className="state state-error">
          No transaction with ID <code>{id}</code>.
        </p>
      )}
      {state.kind === "error" && <p className="state state-error">{state.message}</p>}
      {state.kind === "loaded" && <Details transaction={state.transaction} />}
    </>
  );
}

export function Details({ transaction }: { transaction: Transaction }) {
  const { amount } = transaction;
  return (
    <article className="details">
      <h1>{transaction.description}</h1>
      <p className={`amount amount-${transaction.type.toLowerCase()}`}>{formatSignedMoney(amount, transaction.type)}</p>
      <dl>
        <dt>Transaction ID</dt>
        <dd>
          <code>{transaction.id}</code>
        </dd>
        <dt>Amount</dt>
        <dd>
          {moneyToDecimalString(amount)} {amount.currency}
        </dd>
        <dt>Currency</dt>
        <dd>{amount.currency}</dd>
        <dt>Type</dt>
        <dd>{transaction.type}</dd>
        <dt>Status</dt>
        <dd>
          <span className={`badge badge-${transaction.status.toLowerCase()}`}>{transaction.status}</span>
        </dd>
        <dt>Created</dt>
        <dd>
          {transaction.createdAt.toLocaleString()} <span className="muted">({transaction.createdAtInstant})</span>
        </dd>
      </dl>
    </article>
  );
}
