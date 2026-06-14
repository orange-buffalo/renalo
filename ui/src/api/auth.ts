const authTokenStorageKey = "renalo.authToken";

export type UserType = "USER" | "ADMIN";

export type Profile = {
  username: string;
  type: UserType;
};

export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

export async function createAuthToken(username: string, password: string) {
  const response = await fetch("/api/create-auth-token", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });

  if (!response.ok) {
    throw new ApiError("Invalid username or password", response.status);
  }

  const body = (await response.json()) as { token: string };
  localStorage.setItem(authTokenStorageKey, body.token);
  return body.token;
}

export async function fetchProfile() {
  return apiRequest<Profile>("/api/profile");
}

export function getAuthToken() {
  return localStorage.getItem(authTokenStorageKey);
}

export function clearAuthToken() {
  localStorage.removeItem(authTokenStorageKey);
}

async function apiRequest<T>(path: string) {
  const token = getAuthToken();
  const response = await fetch(path, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });

  if (!response.ok) {
    if (response.status === 401) {
      clearAuthToken();
    }
    throw new ApiError("API request failed", response.status);
  }

  return (await response.json()) as T;
}
