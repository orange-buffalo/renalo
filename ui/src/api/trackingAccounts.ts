import { apiRequest } from "@/api/client";

export type TrackingAccount = {
  id: number;
  name: string;
  currency: string;
  initialBalanceMinor: number;
  isDefault: boolean;
};

export type SaveTrackingAccount = {
  name: string;
  currency: string;
  initialBalanceMinor: number;
  isDefault: boolean;
};

export function fetchTrackingAccounts() {
  return apiRequest<TrackingAccount[]>("/api/tracking/accounts");
}

export function fetchTrackingAccount(accountId: number) {
  return apiRequest<TrackingAccount>(`/api/tracking/accounts/${accountId}`);
}

export function createTrackingAccount(account: SaveTrackingAccount) {
  return apiRequest<TrackingAccount>("/api/tracking/accounts", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(account),
  });
}

export function updateTrackingAccount(
  accountId: number,
  account: SaveTrackingAccount,
) {
  return apiRequest<TrackingAccount>(`/api/tracking/accounts/${accountId}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(account),
  });
}
