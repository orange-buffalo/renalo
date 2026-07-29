import { apiRequest } from "@/api/client";
import type {
  TransactionDateFilterParams,
  TransactionTimeSeries,
  TransactionTimeSeriesGranularity,
} from "@/api/transactions";

export type AccountDashboardSummary = {
  accountId: number;
  accountName: string;
  currency: string;
  totalBalanceMinor: number;
  currentMonthInflowMinor: number;
  currentMonthOutflowMinor: number;
};

export function fetchAccountDashboardSummaries() {
  return apiRequest<AccountDashboardSummary[]>(
    "/api/tracking/dashboard/accounts",
  );
}

export async function fetchNetWorthTimeSeries(
  dateFilter: TransactionDateFilterParams,
  granularity: TransactionTimeSeriesGranularity = "AUTO",
) {
  const params = new URLSearchParams({ granularity });
  if (dateFilter.from) {
    params.set("from", dateFilter.from);
  }
  if (dateFilter.to) {
    params.set("to", dateFilter.to);
  }
  const timeSeries = await apiRequest<TransactionTimeSeries>(
    `/api/tracking/analytics/net-worth/time-series?${params.toString()}`,
  );
  if (
    timeSeries.points.some((point) => !Number.isSafeInteger(point.amountMinor))
  ) {
    throw new Error("Net-worth total exceeds browser-safe integer range");
  }
  return timeSeries;
}
