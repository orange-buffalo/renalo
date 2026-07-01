import {
  ApiError,
  apiRequest,
  getAuthToken as readAuthToken,
  setAuthToken,
} from "@/api/client";

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
  const rememberMeDevice = rememberMe ? getCurrentDeviceLabel() : undefined;
  const response = await fetch("/api/create-auth-token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ username, password, rememberMe, rememberMeDevice }),
  });

  if (!response.ok) {
    throw new ApiError("Invalid username or password", response.status);
  }

  const body = (await response.json()) as { token: string };
  setAuthToken(body.token);
  return body.token;
}

export async function refreshAccessToken() {
  const token = readAuthToken();
  const response = await fetch("/api/refresh-access-token", {
    method: "POST",
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
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

export async function changePassword(
  currentPassword: string,
  newPassword: string,
) {
  return apiRequest<void>("/api/profile/password", {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ currentPassword, newPassword }),
  });
}

function getCurrentDeviceLabel() {
  const userAgent = window.navigator.userAgent;
  const browser = getBrowserName(userAgent);
  const os = getOperatingSystemName(userAgent);
  return `${browser} on ${os}`;
}

function getBrowserName(userAgent: string) {
  if (/Edg\//.test(userAgent)) {
    return "Edge";
  }
  if (/Firefox\//.test(userAgent)) {
    return "Firefox";
  }
  if (/Chrome\//.test(userAgent) || /CriOS\//.test(userAgent)) {
    return "Chrome";
  }
  if (/Safari\//.test(userAgent)) {
    return "Safari";
  }
  return "Browser";
}

function getOperatingSystemName(userAgent: string) {
  if (/Windows NT/.test(userAgent)) {
    return "Windows";
  }
  if (/Android/.test(userAgent)) {
    return "Android";
  }
  if (/iPhone|iPad|iPod/.test(userAgent)) {
    return "iOS";
  }
  if (/Mac OS X/.test(userAgent)) {
    return "macOS";
  }
  if (/Linux/.test(userAgent)) {
    return "Linux";
  }
  return "this device";
}
