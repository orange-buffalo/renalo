import { apiRequest } from "@/api/client";

export type SystemSettings = {
  publicUrl: string;
};

export async function fetchSystemSettings() {
  return apiRequest<SystemSettings>("/api/system-settings");
}
