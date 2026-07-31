import { apiRequest } from "@/api/client";

export type AiChatMessageResponse = {
  content: string;
};

export function sendAiChatMessage(content: string) {
  return apiRequest<AiChatMessageResponse>("/api/ai-chat/messages", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ content }),
  });
}
