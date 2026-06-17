const authTokenStorageKey = "renalo.authToken";

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
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
    }
    throw new ApiError(
      "API request failed",
      response.status,
      await readErrorCode(response),
    );
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}

async function readErrorCode(response: Response) {
  const contentType = response.headers.get("Content-Type");
  if (!contentType?.includes("application/json")) {
    return undefined;
  }

  try {
    const body = (await response.json()) as { code?: string };
    return body.code;
  } catch {
    return undefined;
  }
}
