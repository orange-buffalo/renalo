import { apiRequest } from "@/api/client";

export type FundsTransferAccount = {
  id: number;
  name: string;
  currency: string;
};

export type FundsTransfer = {
  id: number;
  sourceAccount: FundsTransferAccount;
  targetAccount: FundsTransferAccount;
  sourceAmountMinor: number;
  targetAmountMinor: number;
  date: string;
};

export type SaveFundsTransfer = {
  sourceAccountId: number;
  targetAccountId: number;
  sourceAmountMinor: number;
  targetAmountMinor?: number;
  date: string;
};

export type FundsTransferDateFilterParams = {
  from: string | null;
  to: string | null;
};

export type FundsTransferSecondaryFilterParams = {
  sourceAccountIds: number[];
  targetAccountIds: number[];
};

const fundsTransfersPath = "/api/tracking/funds-transfers";

export function fetchFundsTransfers(
  dateFilter?: FundsTransferDateFilterParams,
  secondaryFilters?: FundsTransferSecondaryFilterParams,
) {
  const queryParams = new URLSearchParams();
  if (dateFilter?.from && dateFilter.to) {
    queryParams.set("from", dateFilter.from);
    queryParams.set("to", dateFilter.to);
  }
  if (secondaryFilters?.sourceAccountIds.length) {
    queryParams.set(
      "sourceAccountIds",
      secondaryFilters.sourceAccountIds.join(","),
    );
  }
  if (secondaryFilters?.targetAccountIds.length) {
    queryParams.set(
      "targetAccountIds",
      secondaryFilters.targetAccountIds.join(","),
    );
  }
  const query = queryParams.toString();
  return apiRequest<FundsTransfer[]>(
    query ? `${fundsTransfersPath}?${query}` : fundsTransfersPath,
  );
}

export function fetchFundsTransfer(transferId: number) {
  return apiRequest<FundsTransfer>(`${fundsTransfersPath}/${transferId}`);
}

export function createFundsTransfer(transfer: SaveFundsTransfer) {
  return apiRequest<FundsTransfer>(fundsTransfersPath, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(transfer),
  });
}

export function updateFundsTransfer(
  transferId: number,
  transfer: SaveFundsTransfer,
) {
  return apiRequest<FundsTransfer>(`${fundsTransfersPath}/${transferId}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(transfer),
  });
}

export function deleteFundsTransfer(transferId: number) {
  return apiRequest<void>(`${fundsTransfersPath}/${transferId}`, {
    method: "DELETE",
  });
}
