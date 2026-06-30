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

const fundsTransfersPath = "/api/tracking/funds-transfers";

export function fetchFundsTransfers() {
  return apiRequest<FundsTransfer[]>(fundsTransfersPath);
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
