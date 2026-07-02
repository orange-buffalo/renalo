import { apiRequest } from "@/api/client";

export type ExpenseCategory = {
  id: number;
  name: string;
};

export type SaveExpenseCategory = {
  name: string;
};

export type ExpenseCategoryMergeSummary = {
  sourceCategory: ExpenseCategory;
  expensesCount: number;
  targetCategories: ExpenseCategory[];
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

export function fetchExpenseCategoryMergeSummary(categoryId: number) {
  return apiRequest<ExpenseCategoryMergeSummary>(
    `/api/tracking/expense-categories/${categoryId}/merge-summary`,
  );
}

export function mergeExpenseCategory(
  categoryId: number,
  targetCategoryId: number,
) {
  return apiRequest<void>(
    `/api/tracking/expense-categories/${categoryId}/merge`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ targetCategoryId }),
    },
  );
}
