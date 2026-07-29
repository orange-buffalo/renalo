import { apiRequest } from "@/api/client";
import type {
  TransactionTimeSeriesGranularity,
  TransactionType,
} from "@/api/transactions";

export type DashboardChartFilterMode = "INCLUDE" | "EXCLUDE";

export type DashboardChartPreset = {
  id: number;
  name: string;
  transactionType: TransactionType;
  categoryFilterMode: DashboardChartFilterMode;
  categoryIds: number[];
  accountFilterMode: DashboardChartFilterMode;
  accountIds: number[];
  granularity: TransactionTimeSeriesGranularity;
  isActive: boolean;
};

export type SaveDashboardChartPreset = Omit<
  DashboardChartPreset,
  "id" | "transactionType" | "isActive"
>;

export function fetchDashboardChartPresets() {
  return apiRequest<{ presets: DashboardChartPreset[] }>(
    "/api/tracking/dashboard/chart-presets",
  );
}

export function createDashboardChartPreset(
  transactionType: TransactionType,
  preset: SaveDashboardChartPreset,
) {
  return apiRequest<DashboardChartPreset>(
    `/api/tracking/dashboard/chart-presets/${transactionType}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(preset),
    },
  );
}

export function updateDashboardChartPreset(
  transactionType: TransactionType,
  presetId: number,
  preset: SaveDashboardChartPreset,
) {
  return apiRequest<DashboardChartPreset>(
    `/api/tracking/dashboard/chart-presets/${transactionType}/${presetId}`,
    {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(preset),
    },
  );
}

export function setActiveDashboardChartPreset(
  transactionType: TransactionType,
  presetId: number | null,
) {
  return apiRequest<void>(
    `/api/tracking/dashboard/chart-presets/${transactionType}/active`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ presetId }),
    },
  );
}

export function deleteDashboardChartPreset(
  transactionType: TransactionType,
  presetId: number,
) {
  return apiRequest<void>(
    `/api/tracking/dashboard/chart-presets/${transactionType}/${presetId}`,
    { method: "DELETE" },
  );
}
