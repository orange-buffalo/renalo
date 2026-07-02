import { apiRequest } from "@/api/client";

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
