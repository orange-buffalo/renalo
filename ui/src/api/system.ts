import { apiRequest } from "@/api/client";

export type SystemSettings = {
  publicUrl: string;
  aiChatEnabled: boolean;
};

export async function fetchSystemSettings() {
  return apiRequest<SystemSettings>("/api/system-settings");
}
