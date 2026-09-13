import { Navigate, Route, Routes } from "react-router";
import CardTransactionListPage from "./pages/CardTransactionListPage";
import CardTransactionDetailsPage from "./pages/CardTransactionDetailsPage";

export default function App() {
  return (
    <main className="app">
      <Routes>
        <Route path="/" element={<Navigate to="/card-transactions" replace />} />
        <Route path="/card-transactions" element={<CardTransactionListPage />} />
        <Route path="/card-transactions/:id" element={<CardTransactionDetailsPage />} />
        <Route path="*" element={<p className="state state-error">Page not found.</p>} />
      </Routes>
    </main>
  );
}
