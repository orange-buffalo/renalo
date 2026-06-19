import { apiRequest } from "@/api/client";

export type ExpenseCategory = {
  id: number;
  name: string;
};

export type SaveExpenseCategory = {
  name: string;
};

export function fetchExpenseCategories() {
  return apiRequest<ExpenseCategory[]>("/api/tracking/expense-categories");
}

export function fetchExpenseCategory(categoryId: number) {
  return apiRequest<ExpenseCategory>(
    `/api/tracking/expense-categories/${categoryId}`,
  );
}

export function createExpenseCategory(category: SaveExpenseCategory) {
  return apiRequest<ExpenseCategory>("/api/tracking/expense-categories", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(category),
  });
}

export function updateExpenseCategory(
  categoryId: number,
  category: SaveExpenseCategory,
) {
  return apiRequest<ExpenseCategory>(
    `/api/tracking/expense-categories/${categoryId}`,
    {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(category),
    },
  );
}
