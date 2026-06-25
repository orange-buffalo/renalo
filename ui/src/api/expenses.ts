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
};

export type SaveExpense = {
  trackingAccountId: number;
  categoryId: number;
  date: string;
  amountMinor: number;
  notes?: string | null;
};

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

export function deleteExpense(expenseId: number) {
  return apiRequest<void>(`/api/tracking/expenses/${expenseId}`, {
    method: "DELETE",
  });
}
