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

export type ToshlImportReportEntry = {
  lineNumber: number;
  date: string;
  account: string;
  category: string;
  amountMinor: number;
  currency: string;
  type: "EXPENSE" | "INCOME";
  status: "IMPORTED" | "SKIPPED_DUPLICATE" | "UNMATCHED_TRANSFER";
  reason: string;
};

export type ToshlImportResult = {
  importedExpenses: number;
  importedIncomes: number;
  skippedDuplicateExpenses: number;
  skippedDuplicateIncomes: number;
  importedTransfers: number;
  skippedDuplicateTransfers: number;
  warnings: ToshlImportWarning[];
  report: ToshlImportReportEntry[];
};

export function importToshlCsv(csvContent: string) {
  return apiRequest<ToshlImportResult>("/api/import/toshl", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ csvContent }),
  });
}
