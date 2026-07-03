import { apiRequest } from "@/api/client";

export type TrackingAccount = {
  id: number;
  name: string;
  currency: string;
  initialBalanceMinor: number;
  isDefault: boolean;
  archived: boolean;
};

export type SaveTrackingAccount = {
  name: string;
  currency: string;
  initialBalanceMinor: number;
  isDefault: boolean;
};

export type TrackingAccountMergeSummary = {
  sourceAccount: TrackingAccount;
  expensesCount: number;
  incomesCount: number;
  transfersCount: number;
  targetAccounts: TrackingAccount[];
};

export function fetchTrackingAccounts(options?: { includeArchived?: boolean }) {
  const params = new URLSearchParams();
  if (options?.includeArchived) {
    params.set("includeArchived", "true");
  }
  const query = params.toString();
  return apiRequest<TrackingAccount[]>(
    `/api/tracking/accounts${query ? `?${query}` : ""}`,
  );
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

export function fetchTrackingAccountMergeSummary(accountId: number) {
  return apiRequest<TrackingAccountMergeSummary>(
    `/api/tracking/accounts/${accountId}/merge-summary`,
  );
}

export function mergeTrackingAccount(
  accountId: number,
  targetAccountId: number,
) {
  return apiRequest<void>(`/api/tracking/accounts/${accountId}/merge`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ targetAccountId }),
  });
}

export function archiveTrackingAccount(accountId: number) {
  return apiRequest<TrackingAccount>(
    `/api/tracking/accounts/${accountId}/archive`,
    { method: "POST" },
  );
}

export function unarchiveTrackingAccount(accountId: number) {
  return apiRequest<TrackingAccount>(
    `/api/tracking/accounts/${accountId}/unarchive`,
    { method: "POST" },
  );
}
