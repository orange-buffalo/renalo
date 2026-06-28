import { apiRequest } from "@/api/client";

export type Expense = {
  id: number;
  trackingAccount: {
    id: number;
    name: string;
    currency: string;
  };
  category: {
    id: number;
    name: string;
  };
  date: string;
  amountMinor: number;
  notes?: string | null;
  recurrence?: {
    ruleId: number;
    startDate: string;
    endDate?: string | null;
    instanceDate: string;
    description: string;
  } | null;
};

export type SaveExpense = {
  trackingAccountId: number;
  categoryId: number;
  date: string;
  amountMinor: number;
  notes?: string | null;
  recurringEditScope?:
    | "THIS_OCCURRENCE_ONLY"
    | "THIS_AND_ALL_FOLLOWING_OCCURRENCES"
    | "ALL_OCCURRENCES"
    | null;
  recurrence?: {
    frequency: number;
    interval: "DAY" | "WEEK" | "MONTH";
    endDate?: string | null;
  } | null;
};

export type RecurringExpenseDeleteScope =
  | "THIS_OCCURRENCE_ONLY"
  | "THIS_AND_ALL_FOLLOWING_OCCURRENCES"
  | "ALL_OCCURRENCES";

export function fetchExpenses() {
  return apiRequest<Expense[]>("/api/tracking/expenses");
}

export function fetchExpense(expenseId: number) {
  return apiRequest<Expense>(`/api/tracking/expenses/${expenseId}`);
}

export function createExpense(expense: SaveExpense) {
  return apiRequest<Expense>("/api/tracking/expenses", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(expense),
  });
}

export function updateExpense(expenseId: number, expense: SaveExpense) {
  return apiRequest<Expense>(`/api/tracking/expenses/${expenseId}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(expense),
  });
}

export function deleteExpense(
  expenseId: number,
  recurringDeleteScope?: RecurringExpenseDeleteScope,
) {
  return apiRequest<void>(`/api/tracking/expenses/${expenseId}`, {
    method: "DELETE",
    ...(recurringDeleteScope && {
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ recurringDeleteScope }),
    }),
  });
}
