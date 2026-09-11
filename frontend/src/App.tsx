import { Navigate, Route, Routes } from "react-router";
import TransactionListPage from "./pages/TransactionListPage";
import TransactionDetailsPage from "./pages/TransactionDetailsPage";

export default function App() {
  return (
    <main className="app">
      <Routes>
        <Route path="/" element={<Navigate to="/transactions" replace />} />
        <Route path="/transactions" element={<TransactionListPage />} />
        <Route path="/transactions/:id" element={<TransactionDetailsPage />} />
        <Route path="*" element={<p className="state state-error">Page not found.</p>} />
      </Routes>
    </main>
  );
}
