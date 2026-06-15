const authTokenStorageKey = "renalo.authToken";

export type UserType = "USER" | "ADMIN";

export type Profile = {
  username: string;
  type: UserType;
};

export type ManagedUser = {
  id: number;
  username: string;
  type: UserType;
  currentUser: boolean;
};

export type UsersPage = {
  users: ManagedUser[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
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

export async function fetchUsers(page: number, size: number) {
  const query = new URLSearchParams({
    page: String(page),
    size: String(size),
  });

  return apiRequest<UsersPage>(`/api/users?${query}`);
}

export async function deleteUser(id: number) {
  await apiRequest<void>(`/api/users/${id}`, { method: "DELETE" });
}

export function getAuthToken() {
  return localStorage.getItem(authTokenStorageKey);
}

export function clearAuthToken() {
  localStorage.removeItem(authTokenStorageKey);
}

async function apiRequest<T>(path: string, options: RequestInit = {}) {
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
    throw new ApiError("API request failed", response.status);
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return (await response.json()) as T;
}
