import { apiRequest } from "@/api/client";

export type TransactionType = "EXPENSE" | "INCOME";

export type Transaction = {
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

export type SaveTransaction = {
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

export type RecurringTransactionDeleteScope =
  | "THIS_OCCURRENCE_ONLY"
  | "THIS_AND_ALL_FOLLOWING_OCCURRENCES"
  | "ALL_OCCURRENCES";

export type TransactionApiConfig = {
  type: TransactionType;
  basePath: string;
};

export const expenseTransactionApi: TransactionApiConfig = {
  type: "EXPENSE",
  basePath: "/api/tracking/transactions/EXPENSE",
};

export const incomeTransactionApi: TransactionApiConfig = {
  type: "INCOME",
  basePath: "/api/tracking/transactions/INCOME",
};

export function fetchTransactions(config: TransactionApiConfig) {
  return apiRequest<Transaction[]>(config.basePath);
}

export function fetchTransaction(
  config: TransactionApiConfig,
  transactionId: number,
) {
  return apiRequest<Transaction>(`${config.basePath}/${transactionId}`);
}

export function createTransaction(
  config: TransactionApiConfig,
  transaction: SaveTransaction,
) {
  return apiRequest<Transaction>(config.basePath, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(transaction),
  });
}

export function updateTransaction(
  config: TransactionApiConfig,
  transactionId: number,
  transaction: SaveTransaction,
) {
  return apiRequest<Transaction>(`${config.basePath}/${transactionId}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(transaction),
  });
}

export function deleteTransaction(
  config: TransactionApiConfig,
  transactionId: number,
  recurringDeleteScope?: RecurringTransactionDeleteScope,
) {
  return apiRequest<void>(`${config.basePath}/${transactionId}`, {
    method: "DELETE",
    ...(recurringDeleteScope && {
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ recurringDeleteScope }),
    }),
  });
}
