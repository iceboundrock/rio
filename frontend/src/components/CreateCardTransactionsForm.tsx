import { useRef, useState, type FormEvent } from "react";
import { createCardTransactions, newIdempotencyKey } from "../api/cardTransactions";
import { describeError } from "../api/errors";
import { CURRENCIES, MAX_WIRE_AMOUNT, moneyFromDecimalString, type CurrencyCode } from "../money/money";
import type { CardTransactionType, CreateCardTransactionInput } from "../types/cardTransaction";

/** One row of the form, as typed. Amount stays text until submit; it is parsed with bigint only. */
export interface FormRow {
  description: string;
  amount: string;
  currency: CurrencyCode;
  type: CardTransactionType;
}

export function emptyRow(): FormRow {
  return { description: "", amount: "", currency: "USD", type: "DEBIT" };
}

const CURRENCY_CODES = Object.keys(CURRENCIES) as CurrencyCode[];
const TYPES: CardTransactionType[] = ["DEBIT", "CREDIT"];

/** Client-side check mirroring the server's rules; the server remains authoritative. */
export function validateRows(rows: FormRow[]): { inputs: CreateCardTransactionInput[] | null; rowErrors: (string | null)[] } {
  const inputs: CreateCardTransactionInput[] = [];
  const rowErrors = rows.map((row) => {
    if (row.description.trim() === "") return "Description is required.";
    const amount = moneyFromDecimalString(row.amount, row.currency);
    if (amount === null) {
      const { precision } = CURRENCIES[row.currency];
      return precision === 0
        ? `Enter a positive whole amount for ${row.currency}.`
        : `Enter a positive amount with at most ${precision} decimals for ${row.currency}.`;
    }
    if (amount.amount > MAX_WIRE_AMOUNT) return "Amount is too large.";
    inputs.push({ description: row.description.trim(), amount, type: row.type });
    return null;
  });
  return { inputs: rowErrors.every((e) => e === null) ? inputs : null, rowErrors };
}

/** One logical create as handed to the server: the validated inputs and the key that identifies them. */
export interface Submission {
  idempotencyKey: string;
  inputs: CreateCardTransactionInput[];
}

function sameInputs(a: CreateCardTransactionInput[], b: CreateCardTransactionInput[]): boolean {
  return (
    a.length === b.length &&
    a.every((x, i) => x.description === b[i].description && x.amount.amount === b[i].amount.amount && x.amount.currency === b[i].amount.currency && x.type === b[i].type)
  );
}

/**
 * Picks the submission for this click. When the validated inputs equal the previous attempt's (the same
 * identity the server fingerprints: description, minor units, currency, type, order), the previous key is
 * reused: if that attempt committed but its response was lost, the server replays it instead of creating
 * again, and if it was rejected or rolled back the key is still free and the create proceeds. A changed
 * request gets a fresh key so it cannot collide with the previous one (422 `IDEMPOTENCY_CONFLICT`).
 */
export function submissionFor(previous: Submission | null, inputs: CreateCardTransactionInput[]): Submission {
  return previous !== null && sameInputs(previous.inputs, inputs) ? previous : { idempotencyKey: newIdempotencyKey(), inputs };
}

export interface CreateCardTransactionsFormViewProps {
  rows: FormRow[];
  rowErrors: (string | null)[];
  error: string | null;
  submitting: boolean;
  onChangeRow: (index: number, patch: Partial<FormRow>) => void;
  onAddRow: () => void;
  onRemoveRow: (index: number) => void;
  onSubmit: () => void;
}

/** Pure presentation, so it can be rendered to markup in tests without a DOM. */
export function CreateCardTransactionsFormView({ rows, rowErrors, error, submitting, onChangeRow, onAddRow, onRemoveRow, onSubmit }: CreateCardTransactionsFormViewProps) {
  const submit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    onSubmit();
  };
  return (
    <form className="create-form" onSubmit={submit} aria-busy={submitting}>
      <h2>Add card transactions</h2>
      <p className="muted">Every row is created together, or none of them.</p>
      {rows.map((row, index) => (
        <div className="form-row" key={index}>
          <input
            aria-label={`Description ${index + 1}`}
            placeholder="Description"
            value={row.description}
            disabled={submitting}
            onChange={(e) => onChangeRow(index, { description: e.target.value })}
          />
          <input
            aria-label={`Amount ${index + 1}`}
            placeholder="Amount"
            inputMode="decimal"
            value={row.amount}
            disabled={submitting}
            onChange={(e) => onChangeRow(index, { amount: e.target.value })}
          />
          <select aria-label={`Currency ${index + 1}`} value={row.currency} disabled={submitting} onChange={(e) => onChangeRow(index, { currency: e.target.value as CurrencyCode })}>
            {CURRENCY_CODES.map((code) => (
              <option key={code} value={code}>
                {code}
              </option>
            ))}
          </select>
          <select aria-label={`Type ${index + 1}`} value={row.type} disabled={submitting} onChange={(e) => onChangeRow(index, { type: e.target.value as CardTransactionType })}>
            {TYPES.map((type) => (
              <option key={type} value={type}>
                {type}
              </option>
            ))}
          </select>
          <button type="button" onClick={() => onRemoveRow(index)} disabled={submitting || rows.length === 1} aria-label={`Remove row ${index + 1}`}>
            ×
          </button>
          {rowErrors[index] && <p className="field-error">{rowErrors[index]}</p>}
        </div>
      ))}
      {error && <p className="state state-error">{error}</p>}
      <div className="form-actions">
        <button type="button" onClick={onAddRow} disabled={submitting}>
          Add row
        </button>
        <button type="submit" disabled={submitting}>
          {submitting ? "Saving…" : rows.length === 1 ? "Create" : `Create ${rows.length}`}
        </button>
      </div>
    </form>
  );
}

/** Owns form state; calls `onCreated` after the server has accepted every row. */
export default function CreateCardTransactionsForm({ onCreated }: { onCreated: () => void }) {
  const [rows, setRows] = useState<FormRow[]>([emptyRow()]);
  const [rowErrors, setRowErrors] = useState<(string | null)[]>([null]);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // The last attempt that did not succeed; a resubmit of the same request reuses its key (see `submissionFor`).
  const pending = useRef<Submission | null>(null);

  const onChangeRow = (index: number, patch: Partial<FormRow>) =>
    setRows((current) => current.map((row, i) => (i === index ? { ...row, ...patch } : row)));
  const onAddRow = () => {
    setRows((current) => [...current, emptyRow()]);
    setRowErrors((current) => [...current, null]);
  };
  const onRemoveRow = (index: number) => {
    setRows((current) => current.filter((_, i) => i !== index));
    setRowErrors((current) => current.filter((_, i) => i !== index));
  };

  const onSubmit = async () => {
    const { inputs, rowErrors: errors } = validateRows(rows);
    setRowErrors(errors);
    setError(null);
    if (inputs === null) return;
    setSubmitting(true);
    // `submitting` keeps one click from becoming two; `pending` keeps a retry after an error from
    // becoming a second logical create. A new submission after success gets a new key.
    const submission = submissionFor(pending.current, inputs);
    pending.current = submission;
    try {
      await createCardTransactions(submission.inputs, submission.idempotencyKey);
      pending.current = null;
      setRows([emptyRow()]);
      setRowErrors([null]);
      onCreated();
    } catch (e: unknown) {
      setError(describeError(e));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <CreateCardTransactionsFormView
      rows={rows}
      rowErrors={rowErrors}
      error={error}
      submitting={submitting}
      onChangeRow={onChangeRow}
      onAddRow={onAddRow}
      onRemoveRow={onRemoveRow}
      onSubmit={() => void onSubmit()}
    />
  );
}
