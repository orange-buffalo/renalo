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

export type TransactionDateFilterParams = {
  from: string | null;
  to: string | null;
};

export type TransactionSecondaryFilterParams = {
  categoryIds: number[];
  accountIds: number[];
  notes: string;
};

export type TransactionTimeSeriesGranularity =
  | "AUTO"
  | "DAY"
  | "WEEK"
  | "MONTH";

export type TransactionTimeSeries = {
  granularity: Exclude<TransactionTimeSeriesGranularity, "AUTO">;
  from?: string | null;
  to?: string | null;
  points: Array<{
    bucket: string;
    currency: string;
    amountMinor: number;
  }>;
};

export const expenseTransactionApi: TransactionApiConfig = {
  type: "EXPENSE",
  basePath: "/api/tracking/transactions/EXPENSE",
};

export const incomeTransactionApi: TransactionApiConfig = {
  type: "INCOME",
  basePath: "/api/tracking/transactions/INCOME",
};

export function fetchTransactions(
  config: TransactionApiConfig,
  dateFilter?: TransactionDateFilterParams,
  secondaryFilters?: TransactionSecondaryFilterParams,
) {
  const params = transactionFilterQuery(dateFilter, secondaryFilters);
  const query = params.size ? `?${params.toString()}` : "";
  return apiRequest<Transaction[]>(`${config.basePath}${query}`);
}

export async function fetchTransactionTimeSeries(
  config: TransactionApiConfig,
  dateFilter?: TransactionDateFilterParams,
  secondaryFilters?: TransactionSecondaryFilterParams,
  granularity: TransactionTimeSeriesGranularity = "AUTO",
) {
  const params = transactionFilterQuery(dateFilter, secondaryFilters);
  params.set("granularity", granularity);
  const timeSeries = await apiRequest<TransactionTimeSeries>(
    `/api/tracking/analytics/transactions/${config.type}/time-series?${params.toString()}`,
  );

  if (
    timeSeries.points.some((point) => !Number.isSafeInteger(point.amountMinor))
  ) {
    throw new Error("Time-series total exceeds browser-safe integer range");
  }

  return timeSeries;
}

function transactionFilterQuery(
  dateFilter?: TransactionDateFilterParams,
  secondaryFilters?: TransactionSecondaryFilterParams,
) {
  const params = new URLSearchParams();
  if (dateFilter?.from && dateFilter.to) {
    params.set("from", dateFilter.from);
    params.set("to", dateFilter.to);
  }
  if (secondaryFilters?.categoryIds.length) {
    params.set("categoryIds", secondaryFilters.categoryIds.join(","));
  }
  if (secondaryFilters?.accountIds.length) {
    params.set("accountIds", secondaryFilters.accountIds.join(","));
  }
  const notes = secondaryFilters?.notes.trim();
  if (notes) {
    params.set("notes", notes);
  }
  return params;
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
