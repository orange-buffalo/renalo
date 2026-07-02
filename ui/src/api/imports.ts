import { apiRequest } from "@/api/client";

export type ToshlImportWarning = {
  lineNumber: number;
  date: string;
  account: string;
  amountMinor: number;
  currency: string;
  type: "EXPENSE" | "INCOME";
  description: string;
};

export type ToshlImportResult = {
  importedExpenses: number;
  importedIncomes: number;
  skippedDuplicateExpenses: number;
  skippedDuplicateIncomes: number;
  importedTransfers: number;
  skippedDuplicateTransfers: number;
  warnings: ToshlImportWarning[];
};

export function importToshlCsv(csvContent: string) {
  return apiRequest<ToshlImportResult>("/api/import/toshl", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ csvContent }),
  });
}
