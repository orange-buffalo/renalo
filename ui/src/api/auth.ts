import { ApiError, apiRequest, setAuthToken } from "@/api/client";

export {
  ApiError,
  clearAuthToken,
  getAuthToken,
  redirectToLoginForExpiredSession,
} from "@/api/client";

export type UserType = "USER" | "ADMIN";

export type Profile = {
  username: string;
  type: UserType;
};

export async function createAuthToken(
  username: string,
  password: string,
  rememberMe: boolean,
) {
  const response = await fetch("/api/create-auth-token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ username, password, rememberMe }),
  });

  if (!response.ok) {
    throw new ApiError("Invalid username or password", response.status);
  }

  const body = (await response.json()) as { token: string };
  setAuthToken(body.token);
  return body.token;
}

export async function refreshAccessToken() {
  const response = await fetch("/api/refresh-access-token", {
    method: "POST",
    credentials: "same-origin",
  });

  if (!response.ok) {
    throw new ApiError("Access token refresh failed", response.status);
  }

  const body = (await response.json()) as { token: string | null };
  if (body.token) {
    setAuthToken(body.token);
  }
  return body.token;
}

export function getAuthTokenExpirationTime(token: string) {
  const [, payload] = token.split(".");
  if (!payload) {
    return undefined;
  }

  try {
    const normalizedPayload = payload.replace(/-/g, "+").replace(/_/g, "/");
    const decodedPayload = JSON.parse(window.atob(normalizedPayload)) as {
      exp?: number;
    };
    return typeof decodedPayload.exp === "number"
      ? decodedPayload.exp * 1000
      : undefined;
  } catch {
    return undefined;
  }
}

export async function fetchProfile() {
  return apiRequest<Profile>("/api/profile");
}
