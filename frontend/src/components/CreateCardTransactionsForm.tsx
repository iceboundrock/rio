import { useState, type FormEvent } from "react";
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
    // One key per submission: a second click after an error is a new operation and gets a new key,
    // while `submitting` keeps one click from becoming two. A retry of this same call would reuse it.
    const idempotencyKey = newIdempotencyKey();
    try {
      await createCardTransactions(inputs, idempotencyKey);
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
