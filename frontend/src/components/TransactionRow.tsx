import { useNavigate } from "react-router";
import { formatSignedMoney } from "../money/money";
import type { Transaction } from "../types/transaction";

export default function TransactionRow({ transaction }: { transaction: Transaction }) {
  const navigate = useNavigate();
  const href = `/transactions/${encodeURIComponent(transaction.id)}`;

  return (
    <tr
      className="transaction-row"
      tabIndex={0}
      onClick={() => navigate(href)}
      onKeyDown={(e) => e.key === "Enter" && navigate(href)}
    >
      <td>{transaction.description}</td>
      <td>{transaction.type}</td>
      <td>
        <span className={`badge badge-${transaction.status.toLowerCase()}`}>{transaction.status}</span>
      </td>
      <td className="muted">{transaction.createdAt.toLocaleDateString()}</td>
      <td className={`num amount amount-${transaction.type.toLowerCase()}`}>
        {formatSignedMoney(transaction.amount, transaction.type)}
      </td>
    </tr>
  );
}
