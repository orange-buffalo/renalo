const authTokenStorageKey = "renalo.authToken";
export const accessTokenRefreshLeadTimeMs = 30_000;
let isRedirectingToExpiredSessionLogin = false;
let pendingAccessTokenRefresh: Promise<string | null> | undefined;

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
    readonly details?: string,
  ) {
    super(message);
  }
}

export function getAuthToken() {
  return localStorage.getItem(authTokenStorageKey);
}

export function setAuthToken(token: string) {
  localStorage.setItem(authTokenStorageKey, token);
}

export function clearAuthToken() {
  localStorage.removeItem(authTokenStorageKey);
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

export function isAccessTokenDueForRefresh(token: string) {
  const expiresAt = getAuthTokenExpirationTime(token);
  return !expiresAt || expiresAt - Date.now() <= accessTokenRefreshLeadTimeMs;
}

/**
 * Refreshes the access token, sharing a single in-flight request between the
 * scheduled refresh loop and any API call that hit an expired token.
 */
export function refreshAccessToken() {
  if (!pendingAccessTokenRefresh) {
    pendingAccessTokenRefresh = requestAccessTokenRefresh().finally(() => {
      pendingAccessTokenRefresh = undefined;
    });
  }
  return pendingAccessTokenRefresh;
}

async function requestAccessTokenRefresh(): Promise<string | null> {
  const token = getAuthToken();
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

export async function apiRequest<T>(path: string, options: RequestInit = {}) {
  const response = await apiStreamingRequest(path, options);

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

export async function apiStreamingRequest(
  path: string,
  options: RequestInit = {},
) {
  let response = await sendApiRequest(path, options);

  // The scheduled refresh runs on timers, which browsers stall while the device
  // sleeps or the tab is frozen. A 401 therefore does not mean the session is
  // over: the remember-me cookie may still be able to issue a new access token.
  if (response.status === 401) {
    const refreshedToken = await refreshAccessToken().catch(() => null);
    if (refreshedToken) {
      response = await sendApiRequest(path, options);
    }
  }

  if (!response.ok) {
    if (response.status === 401) {
      clearAuthToken();
      redirectToLoginForExpiredSession();
    }
    const errorBody = await readErrorBody(response);
    throw new ApiError(
      "API request failed",
      response.status,
      errorBody?.code,
      errorBody?.details ?? errorBody?.message,
    );
  }

  return response;
}

async function sendApiRequest(path: string, options: RequestInit) {
  const token = getAuthToken();
  const headers = new Headers(options.headers);
  headers.set(
    "X-Time-Zone",
    Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC",
  );
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  return fetch(path, {
    ...options,
    headers,
  });
}

export function redirectToLoginForExpiredSession() {
  if (isRedirectingToExpiredSessionLogin) {
    return;
  }

  const expiredSessionLoginPath = "/?sessionExpired=true";
  if (
    window.location.pathname === "/" &&
    window.location.search === "?sessionExpired=true"
  ) {
    return;
  }

  isRedirectingToExpiredSessionLogin = true;
  window.location.assign(expiredSessionLoginPath);
}

async function readErrorBody(response: Response) {
  const contentType = response.headers.get("Content-Type");
  if (!contentType?.includes("application/json")) {
    return undefined;
  }

  try {
    return (await response.json()) as {
      code?: string;
      details?: string;
      message?: string;
    };
  } catch {
    return undefined;
  }
}
