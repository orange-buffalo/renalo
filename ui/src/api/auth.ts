import { ApiError, apiRequest, setAuthToken } from "@/api/client";

export { ApiError, clearAuthToken, getAuthToken } from "@/api/client";

export type UserType = "USER" | "ADMIN";

export type Profile = {
  username: string;
  type: UserType;
};

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
  setAuthToken(body.token);
  return body.token;
}

export async function fetchProfile() {
  return apiRequest<Profile>("/api/profile");
}
