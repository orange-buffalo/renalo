import { apiRequest, apiStreamingRequest } from "@/api/client";

export type AiChatConversation = {
  id: number;
  title: string;
  createdAt: string;
  updatedAt: string;
};

export type AiChatToolActivity = {
  id: string;
  label: string;
  status: "IN_PROGRESS" | "COMPLETED" | "CANCELLED";
};

export type AiChatStreamEvent =
  | {
      v: 1;
      seq: number;
      type: "conversation.created";
      conversation: AiChatConversation;
    }
  | { v: 1; seq: number; type: "turn.started" }
  | {
      v: 1;
      seq: number;
      type: "tool.started";
      activityId: string;
      label: string;
    }
  | {
      v: 1;
      seq: number;
      type: "tool.completed";
      activityId: string;
      label: string;
      status: "COMPLETED";
    }
  | { v: 1; seq: number; type: "assistant.delta"; text: string }
  | { v: 1; seq: number; type: "turn.completed" }
  | {
      v: 1;
      seq: number;
      type: "turn.error";
      code: string;
      message: string;
      recoverable: boolean;
    };

export async function streamAiChatMessage(
  content: string,
  conversationId: number | undefined,
  onEvent: (event: AiChatStreamEvent) => void,
  signal: AbortSignal,
) {
  const response = await apiStreamingRequest("/api/ai-chat/messages", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ content, conversationId }),
    signal,
  });
  if (!response.body) {
    throw new Error("The streaming response did not include a body");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let lastSequence = 0;
  let receivedTerminalEvent = false;

  while (true) {
    const { done, value } = await reader.read();
    buffer += decoder.decode(value, { stream: !done });
    const lines = buffer.split("\n");
    buffer = done ? "" : (lines.pop() ?? "");

    for (const line of lines) {
      if (!line.trim()) {
        continue;
      }
      const event = parseStreamEvent(line);
      if (event.seq <= lastSequence) {
        throw new Error("Chat stream events arrived out of order");
      }
      lastSequence = event.seq;
      onEvent(event);
      receivedTerminalEvent =
        event.type === "turn.completed" || event.type === "turn.error";
    }

    if (done) {
      break;
    }
  }

  if (!receivedTerminalEvent) {
    throw new Error("Chat stream ended before the turn completed");
  }
}

export async function fetchAiChatConversations() {
  const response = await apiRequest<{ conversations: AiChatConversation[] }>(
    "/api/ai-chat/conversations",
  );
  return response.conversations;
}

export function renameAiChatConversation(
  conversationId: number,
  title: string,
) {
  return apiRequest<AiChatConversation>(
    `/api/ai-chat/conversations/${conversationId}`,
    {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title }),
    },
  );
}

export function deleteAiChatConversation(conversationId: number) {
  return apiRequest<void>(`/api/ai-chat/conversations/${conversationId}`, {
    method: "DELETE",
  });
}

function parseStreamEvent(line: string): AiChatStreamEvent {
  const event = JSON.parse(line) as Record<string, unknown>;
  if (
    event.v !== 1 ||
    typeof event.seq !== "number" ||
    typeof event.type !== "string" ||
    !streamEventTypes.has(event.type)
  ) {
    throw new Error("Unsupported chat stream event");
  }
  return event as AiChatStreamEvent;
}

const streamEventTypes = new Set([
  "conversation.created",
  "turn.started",
  "tool.started",
  "tool.completed",
  "assistant.delta",
  "turn.completed",
  "turn.error",
]);
