import TransactionRow from "./TransactionRow";
import type { Transaction } from "../types/transaction";

export default function TransactionList({ transactions }: { transactions: Transaction[] }) {
  return (
    <table className="transactions">
      <thead>
        <tr>
          <th>Description</th>
          <th>Type</th>
          <th>Status</th>
          <th>Date</th>
          <th className="num">Amount</th>
        </tr>
      </thead>
      <tbody>
        {transactions.map((transaction) => (
          <TransactionRow key={transaction.id} transaction={transaction} />
        ))}
      </tbody>
    </table>
  );
}
