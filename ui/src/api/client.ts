const authTokenStorageKey = "renalo.authToken";
let isRedirectingToExpiredSessionLogin = false;

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

export async function apiRequest<T>(path: string, options: RequestInit = {}) {
  const token = getAuthToken();
  const headers = new Headers(options.headers);
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(path, {
    ...options,
    headers,
  });

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

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
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
