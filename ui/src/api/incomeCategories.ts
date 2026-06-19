import { apiRequest } from "@/api/client";

export type IncomeCategory = {
  id: number;
  name: string;
};

export type SaveIncomeCategory = {
  name: string;
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
