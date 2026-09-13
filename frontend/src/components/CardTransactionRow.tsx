import { useNavigate } from "react-router";
import { formatSignedMoney } from "../money/money";
import type { CardTransaction } from "../types/cardTransaction";

export default function CardTransactionRow({ cardTransaction }: { cardTransaction: CardTransaction }) {
  const navigate = useNavigate();
  const href = `/card-transactions/${encodeURIComponent(cardTransaction.id)}`;

  return (
    <tr
      className="card-transaction-row"
      tabIndex={0}
      onClick={() => navigate(href)}
      onKeyDown={(e) => e.key === "Enter" && navigate(href)}
    >
      <td>{cardTransaction.description}</td>
      <td>{cardTransaction.type}</td>
      <td>
        <span className={`badge badge-${cardTransaction.status.toLowerCase()}`}>{cardTransaction.status}</span>
      </td>
      <td className="muted">{cardTransaction.createdAt.toLocaleDateString()}</td>
      <td className={`num amount amount-${cardTransaction.type.toLowerCase()}`}>
        {formatSignedMoney(cardTransaction.amount, cardTransaction.type)}
      </td>
    </tr>
  );
}
