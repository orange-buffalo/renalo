import { apiRequest } from "@/api/client";
import type { Transaction } from "@/api/transactions";

export type RefundSearch = {
  currency: string;
  amountMinor: number;
  period: "THREE_MONTHS" | "SIX_MONTHS" | "YEAR" | "TWO_YEARS" | "FULL_HISTORY";
};

export type ExpenseRefundMatch = {
  transactionId: number;
  kind: "EXPENSE" | "RECURRING_SERIES";
  fingerprint: string;
  payments: Transaction[];
  removedExpenseCount: number;
  stopsRecurrence: boolean;
};

export function searchExpenseRefunds(search: RefundSearch) {
  return apiRequest<ExpenseRefundMatch[]>(
    "/api/tracking/expense-refunds/search",
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(search),
    },
  );
}

export function saveExpenseRefund(
  search: RefundSearch,
  match: ExpenseRefundMatch,
) {
  return apiRequest<void>("/api/tracking/expense-refunds", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      search,
      transactionId: match.transactionId,
      kind: match.kind,
      fingerprint: match.fingerprint,
    }),
  });
}
