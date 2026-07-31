import { ChevronDown, Plus, Send01, Stop } from "@untitledui/icons";
import { lazy, Suspense, useEffect, useRef, useState } from "react";
import type { AiChatStreamEvent, AiChatToolActivity } from "@/api/aiChat";
import { streamAiChatMessage } from "@/api/aiChat";
import { PageLayout } from "@/components/PageLayout";
import { Button } from "@/components/untitled/base/buttons/button";
import { Dropdown } from "@/components/untitled/base/dropdown/dropdown";
import { TextArea } from "@/components/untitled/base/textarea/textarea";

type ChatMessage = {
  id: number;
  author: "You" | "Renalo";
  content: string;
  toolActivities?: AiChatToolActivity[];
  isStreaming?: boolean;
};

type Conversation = {
  id: number;
  title: string;
  messages: ChatMessage[];
};

const AiMarkdown = lazy(async () => ({
  default: (await import("@/components/ai-chat/AiMarkdown")).AiMarkdown,
}));

const initialConversation: Conversation = {
  id: 1,
  title: "Conversation 1",
  messages: [],
};

export function AiChatPage() {
  const feedRef = useRef<HTMLDivElement>(null);
  const activeRequestRef = useRef<AbortController>(null);
  const [conversations, setConversations] = useState<Conversation[]>([
    initialConversation,
  ]);
  const [activeConversationId, setActiveConversationId] = useState(1);
  const [nextConversationId, setNextConversationId] = useState(2);
  const [nextMessageId, setNextMessageId] = useState(1);
  const [draft, setDraft] = useState("");
  const [isSending, setIsSending] = useState(false);
  const [error, setError] = useState<string>();
  const activeConversation = conversations.find(
    (conversation) => conversation.id === activeConversationId,
  );

  useEffect(() => {
    if (!activeConversation) {
      return;
    }

    const feed = feedRef.current;
    if (feed) {
      feed.scrollTop = feed.scrollHeight;
    }
  }, [activeConversation]);

  useEffect(() => () => activeRequestRef.current?.abort(), []);

  function createConversation() {
    const conversation: Conversation = {
      id: nextConversationId,
      title: `Conversation ${nextConversationId}`,
      messages: [],
    };
    setConversations((current) => [...current, conversation]);
    setActiveConversationId(conversation.id);
    setNextConversationId((current) => current + 1);
    setDraft("");
    setError(undefined);
  }

  async function sendMessage() {
    const content = draft.trim();
    if (!content || isSending || !activeConversation) {
      return;
    }

    const conversationId = activeConversation.id;
    const userMessageId = nextMessageId;
    const assistantMessageId = nextMessageId + 1;
    const userMessage: ChatMessage = {
      id: userMessageId,
      author: "You",
      content,
    };
    const assistantMessage: ChatMessage = {
      id: assistantMessageId,
      author: "Renalo",
      content: "",
      toolActivities: [],
      isStreaming: true,
    };
    setNextMessageId((current) => current + 2);
    setConversations((current) =>
      appendMessages(current, conversationId, [userMessage, assistantMessage]),
    );
    setDraft("");
    setError(undefined);
    setIsSending(true);
    const abortController = new AbortController();
    activeRequestRef.current = abortController;

    try {
      await streamAiChatMessage(
        content,
        (event) => {
          applyStreamEvent(conversationId, assistantMessageId, event);
        },
        abortController.signal,
      );
    } catch (requestError) {
      if (!isAbortError(requestError)) {
        setError(
          "The response was interrupted. Partial content remains in this conversation.",
        );
      }
      setConversations((current) =>
        finishStreamingMessage(
          current,
          conversationId,
          assistantMessageId,
          isAbortError(requestError),
        ),
      );
    } finally {
      if (activeRequestRef.current === abortController) {
        activeRequestRef.current = null;
      }
      setIsSending(false);
    }
  }

  function applyStreamEvent(
    conversationId: number,
    messageId: number,
    event: AiChatStreamEvent,
  ) {
    if (event.type === "turn.error") {
      setError(event.message);
    }
    setConversations((current) =>
      updateMessage(current, conversationId, messageId, (message) =>
        applyEventToMessage(message, event),
      ),
    );
  }

  function cancelResponse() {
    activeRequestRef.current?.abort();
  }

  return (
    <PageLayout
      title="Chat"
      description="Start a conversation and explore the shape of the chat experience."
      className="ai-chat-page-surface"
    >
      <section className="standard-page-panel ai-chat-panel">
        <div className="ai-chat-toolbar">
          <Dropdown.Root>
            <Button
              aria-label="Select conversation"
              className="ai-chat-conversation-trigger"
              color="tertiary"
              size="sm"
              iconTrailing={ChevronDown}
              isDisabled={isSending}
            >
              {activeConversation?.title}
            </Button>
            <Dropdown.Popover placement="bottom">
              <Dropdown.Menu
                aria-label="Conversations"
                selectionMode="single"
                selectedKeys={[String(activeConversationId)]}
              >
                {conversations.map((conversation) => (
                  <Dropdown.Item
                    id={String(conversation.id)}
                    key={conversation.id}
                    label={conversation.title}
                    onAction={() => {
                      setActiveConversationId(conversation.id);
                      setDraft("");
                      setError(undefined);
                    }}
                  />
                ))}
              </Dropdown.Menu>
            </Dropdown.Popover>
          </Dropdown.Root>
          <Button
            aria-label="New conversation"
            className="ai-chat-new-conversation-button"
            color="secondary"
            size="sm"
            iconLeading={Plus}
            isDisabled={isSending}
            onPress={createConversation}
          />
        </div>

        <div
          className="ai-chat-feed"
          ref={feedRef}
          role="log"
          aria-label="Message feed"
          aria-busy={isSending}
          aria-live="polite"
        >
          {activeConversation?.messages.length ? (
            activeConversation.messages.map((message) => (
              <article
                className={`ai-chat-message ai-chat-message--${message.author === "You" ? "user" : "assistant"}`}
                data-chat-author={message.author}
                key={message.id}
              >
                <div className="ai-chat-message-body">
                  {message.author === "Renalo" &&
                    message.toolActivities?.map((activity) => (
                      <div
                        className="ai-chat-tool-activity"
                        data-tool-status={activity.status}
                        key={activity.id}
                      >
                        <span
                          className="ai-chat-tool-activity-dot"
                          aria-hidden="true"
                        />
                        {activity.label}
                        {activity.status === "CANCELLED" && (
                          <span className="ai-chat-tool-activity-status">
                            · Stopped
                          </span>
                        )}
                      </div>
                    ))}
                  <div className="ai-chat-message-content">
                    {message.author === "Renalo" ? (
                      <Suspense
                        fallback={
                          <div className="ai-chat-markdown-loading">
                            Formatting response...
                          </div>
                        }
                      >
                        <AiMarkdown isStreaming={message.isStreaming}>
                          {message.content}
                        </AiMarkdown>
                      </Suspense>
                    ) : (
                      message.content
                    )}
                  </div>
                </div>
              </article>
            ))
          ) : (
            <div className="ai-chat-empty-state">
              <h2>What would you like to explore?</h2>
              <p>Send a message to begin this conversation.</p>
            </div>
          )}
        </div>

        <div className="ai-chat-composer">
          {error && <p className="ai-chat-error">{error}</p>}
          <div className="ai-chat-composer-input">
            <TextArea
              aria-label="Message"
              placeholder="Write a message..."
              rows={3}
              textAreaClassName="ai-chat-composer-textarea"
              value={draft}
              isDisabled={isSending}
              onChange={setDraft}
              onKeyDown={(event) => {
                if (event.key === "Enter" && !event.shiftKey) {
                  event.preventDefault();
                  void sendMessage();
                }
              }}
            />
            <Button
              aria-label={isSending ? "Stop response" : "Send message"}
              className="ai-chat-send-button"
              color="tertiary"
              size="sm"
              iconLeading={isSending ? Stop : Send01}
              isDisabled={!isSending && !draft.trim()}
              onPress={() =>
                isSending ? cancelResponse() : void sendMessage()
              }
            />
          </div>
        </div>
      </section>
    </PageLayout>
  );
}

function appendMessages(
  conversations: Conversation[],
  conversationId: number,
  messages: ChatMessage[],
) {
  return conversations.map((conversation) =>
    conversation.id === conversationId
      ? { ...conversation, messages: [...conversation.messages, ...messages] }
      : conversation,
  );
}

function updateMessage(
  conversations: Conversation[],
  conversationId: number,
  messageId: number,
  update: (message: ChatMessage) => ChatMessage,
) {
  return conversations.map((conversation) =>
    conversation.id === conversationId
      ? {
          ...conversation,
          messages: conversation.messages.map((message) =>
            message.id === messageId ? update(message) : message,
          ),
        }
      : conversation,
  );
}

function applyEventToMessage(
  message: ChatMessage,
  event: AiChatStreamEvent,
): ChatMessage {
  switch (event.type) {
    case "assistant.delta":
      return { ...message, content: message.content + event.text };
    case "tool.started":
      return {
        ...message,
        toolActivities: [
          ...(message.toolActivities ?? []),
          {
            id: event.activityId,
            label: event.label,
            status: "IN_PROGRESS",
          },
        ],
      };
    case "tool.completed":
      return {
        ...message,
        toolActivities: (message.toolActivities ?? []).map((activity) =>
          activity.id === event.activityId
            ? { ...activity, label: event.label, status: event.status }
            : activity,
        ),
      };
    case "turn.completed":
    case "turn.error":
      return { ...message, isStreaming: false };
    case "turn.started":
      return message;
  }
}

function finishStreamingMessage(
  conversations: Conversation[],
  conversationId: number,
  messageId: number,
  removeIfEmpty: boolean,
) {
  return conversations.map((conversation) => {
    if (conversation.id !== conversationId) {
      return conversation;
    }
    const message = conversation.messages.find((item) => item.id === messageId);
    if (
      removeIfEmpty &&
      message &&
      !message.content &&
      !message.toolActivities?.length
    ) {
      return {
        ...conversation,
        messages: conversation.messages.filter((item) => item.id !== messageId),
      };
    }
    return {
      ...conversation,
      messages: conversation.messages.map((item) =>
        item.id === messageId
          ? {
              ...item,
              isStreaming: false,
              toolActivities: item.toolActivities?.map((activity) =>
                activity.status === "IN_PROGRESS"
                  ? { ...activity, status: "CANCELLED" as const }
                  : activity,
              ),
            }
          : item,
      ),
    };
  });
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === "AbortError";
}
