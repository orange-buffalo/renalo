import { apiRequest } from "@/api/client";

export type AccountActivationStatus = {
  username: string;
};

export function fetchAccountActivationStatus(token: string) {
  return apiRequest<AccountActivationStatus>(
    `/api/account-activation?token=${encodeURIComponent(token)}`,
  );
}

export function activateAccount(
  token: string,
  password: string,
  passwordConfirmation: string,
) {
  return apiRequest<void>(
    `/api/account-activation?token=${encodeURIComponent(token)}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ password, passwordConfirmation }),
    },
  );
}
