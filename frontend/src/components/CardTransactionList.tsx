import CardTransactionRow from "./CardTransactionRow";
import type { CardTransaction } from "../types/cardTransaction";

export default function CardTransactionList({ cardTransactions }: { cardTransactions: CardTransaction[] }) {
  return (
    <table className="card-transactions">
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
        {cardTransactions.map((cardTransaction) => (
          <CardTransactionRow key={cardTransaction.id} cardTransaction={cardTransaction} />
        ))}
      </tbody>
    </table>
  );
}
