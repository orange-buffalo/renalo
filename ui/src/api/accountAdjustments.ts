import { apiRequest } from "@/api/client";

export type AccountAdjustment = {
  id: number;
  adjustmentAmountMinor: number;
  createdAt: string;
};

export type AccountAdjustmentsData = {
  accountId: number;
  accountName: string;
  currency: string;
  currentBalanceMinor: number;
  baseBalanceMinor: number;
  adjustments: AccountAdjustment[];
};

export function fetchAccountAdjustments(accountId: number) {
  return apiRequest<AccountAdjustmentsData>(
    `/api/tracking/accounts/${accountId}/adjustments`,
  );
}

export function createAccountAdjustment(
  accountId: number,
  adjustmentAmountMinor: number,
) {
  return apiRequest<AccountAdjustment>(
    `/api/tracking/accounts/${accountId}/adjustments`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ adjustmentAmountMinor }),
    },
  );
}

export function deleteAccountAdjustment(
  accountId: number,
  adjustmentId: number,
) {
  return apiRequest<void>(
    `/api/tracking/accounts/${accountId}/adjustments/${adjustmentId}`,
    { method: "DELETE" },
  );
}
