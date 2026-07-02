import { apiRequest } from "@/api/client";

export type IncomeCategory = {
  id: number;
  name: string;
};

export type SaveIncomeCategory = {
  name: string;
};

export type IncomeCategoryMergeSummary = {
  sourceCategory: IncomeCategory;
  incomesCount: number;
  targetCategories: IncomeCategory[];
};

export function fetchIncomeCategories() {
  return apiRequest<IncomeCategory[]>("/api/tracking/income-categories");
}

export function fetchIncomeCategory(categoryId: number) {
  return apiRequest<IncomeCategory>(
    `/api/tracking/income-categories/${categoryId}`,
  );
}

export function createIncomeCategory(category: SaveIncomeCategory) {
  return apiRequest<IncomeCategory>("/api/tracking/income-categories", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(category),
  });
}

export function updateIncomeCategory(
  categoryId: number,
  category: SaveIncomeCategory,
) {
  return apiRequest<IncomeCategory>(
    `/api/tracking/income-categories/${categoryId}`,
    {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(category),
    },
  );
}

export function fetchIncomeCategoryMergeSummary(categoryId: number) {
  return apiRequest<IncomeCategoryMergeSummary>(
    `/api/tracking/income-categories/${categoryId}/merge-summary`,
  );
}

export function mergeIncomeCategory(
  categoryId: number,
  targetCategoryId: number,
) {
  return apiRequest<void>(
    `/api/tracking/income-categories/${categoryId}/merge`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ targetCategoryId }),
    },
  );
}
