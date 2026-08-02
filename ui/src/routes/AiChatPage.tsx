import {
  ChevronDown,
  DotsHorizontal,
  Edit01,
  Plus,
  Send01,
  Stop,
  Trash01,
  XClose,
} from "@untitledui/icons";
import {
  lazy,
  Suspense,
  useEffect,
  useEffectEvent,
  useRef,
  useState,
} from "react";
import type {
  AiChatChart,
  AiChatConversation,
  AiChatHistoryItem,
  AiChatStreamEvent,
  AiChatToolActivity,
} from "@/api/aiChat";
import {
  deleteAiChatConversation,
  fetchAiChatConversationHistory,
  fetchAiChatConversations,
  renameAiChatConversation,
  streamAiChatMessage,
} from "@/api/aiChat";
import { AiChatChart as AiChatChartView } from "@/components/ai-chat/AiChatChart";
import { ConfirmationDialog } from "@/components/ConfirmationDialog";
import { PageLayout } from "@/components/PageLayout";
import { LoadingIndicator } from "@/components/untitled/application/loading-indicator/loading-indicator";
import {
  Dialog,
  Modal,
  ModalOverlay,
} from "@/components/untitled/application/modals/modal";
import { Button } from "@/components/untitled/base/buttons/button";
import { Dropdown } from "@/components/untitled/base/dropdown/dropdown";
import { Input } from "@/components/untitled/base/input/input";
import { TextArea } from "@/components/untitled/base/textarea/textarea";

type ChatMessage = {
  id: number;
  author: "You" | "Renalo";
  content: string;
  charts?: AiChatChart[];
  streamItems?: AssistantStreamItem[];
  isStreaming?: boolean;
  thinkingLabel?: string;
};

type AssistantStreamItem =
  | { type: "content"; id: string; content: string }
  | { type: "chart"; chart: AiChatChart }
  | { type: "tools"; id: string; activities: AiChatToolActivity[] };

type Conversation = {
  clientId: string;
  id?: number;
  title: string;
  createdAt?: string;
  updatedAt?: string;
  messages: ChatMessage[];
  historyStatus:
    | "NOT_LOADED"
    | "LOADING"
    | "AVAILABLE"
    | "TEMPORARILY_UNAVAILABLE";
};

const AiMarkdown = lazy(async () => ({
  default: (await import("@/components/ai-chat/AiMarkdown")).AiMarkdown,
}));

const initialConversation = createDraftConversation("draft-1");

export function AiChatPage() {
  const feedRef = useRef<HTMLDivElement>(null);
  const activeRequestRef = useRef<AbortController>(null);
  const historyRequestRef = useRef<AbortController>(null);
  const nextDraftIdRef = useRef(2);
  const [conversations, setConversations] = useState<Conversation[]>([
    initialConversation,
  ]);
  const [activeConversationClientId, setActiveConversationClientId] = useState(
    initialConversation.clientId,
  );
  const [nextMessageId, setNextMessageId] = useState(1);
  const [draft, setDraft] = useState("");
  const [isSending, setIsSending] = useState(false);
  const [error, setError] = useState<string>();
  const [conversationToRename, setConversationToRename] =
    useState<Conversation>();
  const [renameTitle, setRenameTitle] = useState("");
  const [renameError, setRenameError] = useState<string>();
  const [isRenaming, setIsRenaming] = useState(false);
  const [conversationToDelete, setConversationToDelete] =
    useState<Conversation>();
  const [isDeleting, setIsDeleting] = useState(false);
  const activeConversation = conversations.find(
    (conversation) => conversation.clientId === activeConversationClientId,
  );
  const loadInitialConversationHistory = useEffectEvent(
    loadConversationHistory,
  );

  useEffect(() => {
    let isActive = true;
    void fetchAiChatConversations()
      .then((persistedConversations) => {
        if (!isActive) {
          return;
        }
        const sortedPersisted = [...persistedConversations].sort(
          compareConversationsByUpdatedAt,
        );
        setConversations((current) =>
          sortConversations([
            ...sortedPersisted.map((conversation) => {
              const existing = current.find(
                (item) => item.id === conversation.id,
              );
              return existing
                ? { ...existing, ...conversation }
                : fromPersistedConversation(conversation);
            }),
            ...current.filter((conversation) => conversation.id === undefined),
          ]),
        );
        if (sortedPersisted[0]) {
          const conversationClientId = `conversation-${sortedPersisted[0].id}`;
          setActiveConversationClientId(conversationClientId);
          void loadInitialConversationHistory(
            conversationClientId,
            sortedPersisted[0].id,
          );
        }
      })
      .catch(() => {
        if (isActive) {
          setError("Saved chats could not be loaded. Try refreshing the page.");
        }
      });
    return () => {
      isActive = false;
      activeRequestRef.current?.abort();
      historyRequestRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    if (!activeConversation) {
      return;
    }
    const feed = feedRef.current;
    if (feed) {
      feed.scrollTop = feed.scrollHeight;
    }
  }, [activeConversation]);

  function createConversation() {
    const conversation = createDraftConversation(
      `draft-${nextDraftIdRef.current++}`,
    );
    setConversations((current) => [
      ...current.filter((item) => item.id !== undefined),
      conversation,
    ]);
    setActiveConversationClientId(conversation.clientId);
    setDraft("");
    setError(undefined);
  }

  async function loadConversationHistory(
    conversationClientId: string,
    conversationId: number,
  ) {
    historyRequestRef.current?.abort();
    const abortController = new AbortController();
    historyRequestRef.current = abortController;
    setConversations((current) =>
      current.map((conversation) =>
        conversation.clientId === conversationClientId
          ? { ...conversation, historyStatus: "LOADING" }
          : conversation,
      ),
    );

    try {
      const history = await fetchAiChatConversationHistory(
        conversationId,
        abortController.signal,
      );
      if (abortController.signal.aborted) {
        return;
      }
      setConversations((current) =>
        current.map((conversation) => {
          if (conversation.clientId !== conversationClientId) {
            return conversation;
          }
          if (history.status === "TEMPORARILY_UNAVAILABLE") {
            return {
              ...conversation,
              messages: [],
              historyStatus: "TEMPORARILY_UNAVAILABLE",
            };
          }
          return {
            ...conversation,
            messages: history.messages.map((message, index) => ({
              id: -(index + 1),
              author: message.role === "USER" ? "You" : "Renalo",
              content: message.content,
              charts: message.charts,
              streamItems:
                message.role === "ASSISTANT"
                  ? historyAssistantItems(message.items)
                  : undefined,
            })),
            historyStatus: "AVAILABLE",
          };
        }),
      );
    } catch (historyError) {
      if (!isAbortError(historyError)) {
        setConversations((current) =>
          current.map((conversation) =>
            conversation.clientId === conversationClientId
              ? {
                  ...conversation,
                  historyStatus: "TEMPORARILY_UNAVAILABLE",
                }
              : conversation,
          ),
        );
      }
    } finally {
      if (historyRequestRef.current === abortController) {
        historyRequestRef.current = null;
      }
    }
  }

  async function sendMessage() {
    const content = draft.trim();
    if (
      !content ||
      isSending ||
      !activeConversation ||
      activeConversation.historyStatus !== "AVAILABLE"
    ) {
      return;
    }

    const conversationClientId = activeConversation.clientId;
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
      charts: [],
      streamItems: [],
      isStreaming: true,
      thinkingLabel: "Thinking",
    };
    setNextMessageId((current) => current + 2);
    setConversations((current) =>
      appendMessages(current, conversationClientId, [
        userMessage,
        assistantMessage,
      ]),
    );
    setDraft("");
    setError(undefined);
    setIsSending(true);
    const abortController = new AbortController();
    activeRequestRef.current = abortController;

    try {
      await streamAiChatMessage(
        content,
        activeConversation.id,
        (event) => {
          applyStreamEvent(conversationClientId, assistantMessageId, event);
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
          conversationClientId,
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
    conversationClientId: string,
    messageId: number,
    event: AiChatStreamEvent,
  ) {
    if (event.type === "turn.error") {
      setError(event.message);
    }
    setConversations((current) =>
      sortConversations(
        current.map((conversation) => {
          if (conversation.clientId !== conversationClientId) {
            return conversation;
          }
          const metadata = getConversationMetadata(event);
          const withMetadata = metadata
            ? { ...conversation, ...metadata }
            : conversation;
          return {
            ...withMetadata,
            messages: withMetadata.messages.map((message) =>
              message.id === messageId
                ? applyEventToMessage(message, event)
                : message,
            ),
          };
        }),
      ),
    );
  }

  function openRenameDialog() {
    if (!activeConversation?.id) {
      return;
    }
    setConversationToRename(activeConversation);
    setRenameTitle(activeConversation.title);
    setRenameError(undefined);
  }

  async function saveConversationTitle() {
    if (!conversationToRename?.id || isRenaming) {
      return;
    }
    const title = renameTitle.trim();
    if (!title) {
      setRenameError("Enter a chat name.");
      return;
    }
    if (title.length > 100) {
      setRenameError("Chat names can be up to 100 characters.");
      return;
    }

    setIsRenaming(true);
    setRenameError(undefined);
    try {
      const renamed = await renameAiChatConversation(
        conversationToRename.id,
        title,
      );
      setConversations((current) =>
        sortConversations(
          current.map((conversation) =>
            conversation.id === renamed.id
              ? { ...conversation, ...renamed }
              : conversation,
          ),
        ),
      );
      setConversationToRename(undefined);
    } catch {
      setRenameError("The chat could not be renamed. Try again.");
    } finally {
      setIsRenaming(false);
    }
  }

  function requestDeleteConversation() {
    if (!activeConversation) {
      return;
    }
    if (activeConversation.id === undefined) {
      createConversation();
      return;
    }
    setConversationToDelete(activeConversation);
  }

  async function confirmDeleteConversation() {
    if (!conversationToDelete?.id || isDeleting) {
      return;
    }
    setIsDeleting(true);
    try {
      const deletedConversationId = conversationToDelete.id;
      await deleteAiChatConversation(deletedConversationId);
      setConversations((current) => {
        const remaining = sortConversations(
          current.filter(
            (conversation) => conversation.id !== deletedConversationId,
          ),
        );
        const replacement =
          remaining[0] ??
          createDraftConversation(`draft-${nextDraftIdRef.current++}`);
        setActiveConversationClientId(replacement.clientId);
        return remaining.length > 0 ? remaining : [replacement];
      });
      setDraft("");
      setConversationToDelete(undefined);
    } catch {
      setConversationToDelete(undefined);
      setError("The chat could not be deleted. Try again.");
    } finally {
      setIsDeleting(false);
    }
  }

  return (
    <PageLayout
      title="Chat"
      description="Ask questions about your accounts, transactions, spending, and net worth."
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
                selectedKeys={[activeConversationClientId]}
              >
                {conversations.map((conversation) => (
                  <Dropdown.Item
                    id={conversation.clientId}
                    key={conversation.clientId}
                    textValue={conversation.title}
                    onAction={() => {
                      setActiveConversationClientId(conversation.clientId);
                      setDraft("");
                      setError(undefined);
                      if (
                        conversation.id !== undefined &&
                        conversation.historyStatus === "NOT_LOADED"
                      ) {
                        void loadConversationHistory(
                          conversation.clientId,
                          conversation.id,
                        );
                      }
                    }}
                  >
                    <span className="ai-chat-conversation-option">
                      <span data-chat-conversation-title>
                        {conversation.title}
                      </span>
                      {conversation.updatedAt && (
                        <span className="ai-chat-conversation-option-time">
                          {formatConversationUpdatedAt(conversation.updatedAt)}
                        </span>
                      )}
                    </span>
                  </Dropdown.Item>
                ))}
              </Dropdown.Menu>
            </Dropdown.Popover>
          </Dropdown.Root>

          <div className="ai-chat-toolbar-actions">
            <Dropdown.Root>
              <Button
                aria-label="Chat actions"
                color="tertiary"
                size="sm"
                iconLeading={DotsHorizontal}
                isDisabled={isSending || activeConversation?.id === undefined}
              />
              <Dropdown.Popover placement="bottom right">
                <Dropdown.Menu aria-label="Chat actions" selectionMode="none">
                  {activeConversation?.id !== undefined && (
                    <Dropdown.Item
                      label="Rename chat"
                      icon={Edit01}
                      selectionIndicator="none"
                      onAction={openRenameDialog}
                    />
                  )}
                  {activeConversation?.id !== undefined && (
                    <Dropdown.Separator />
                  )}
                  <Dropdown.Item
                    className="ai-chat-delete-menu-item"
                    label="Delete chat"
                    icon={Trash01}
                    selectionIndicator="none"
                    onAction={requestDeleteConversation}
                  />
                </Dropdown.Menu>
              </Dropdown.Popover>
            </Dropdown.Root>
            <Button
              aria-label="New conversation"
              className="ai-chat-new-conversation-button"
              color="secondary"
              size="sm"
              iconLeading={Plus}
              isDisabled={isSending || activeConversation?.id === undefined}
              onPress={createConversation}
            />
          </div>
        </div>

        <div
          className="ai-chat-feed"
          ref={feedRef}
          role="log"
          aria-label="Message feed"
          aria-busy={
            isSending || activeConversation?.historyStatus === "LOADING"
          }
          aria-live="polite"
        >
          {activeConversation?.historyStatus === "LOADING" ? (
            <div className="ai-chat-history-loading">
              <LoadingIndicator
                type="line-spinner"
                size="md"
                label="Loading chat history..."
              />
            </div>
          ) : activeConversation?.historyStatus ===
            "TEMPORARILY_UNAVAILABLE" ? (
            <div className="ai-chat-history-unavailable">
              <h2>Chat history is temporarily unavailable</h2>
              <p>
                This saved chat is unchanged. Retry after the external provider
                state is available again.
              </p>
              <Button
                color="secondary"
                size="sm"
                onPress={() => {
                  if (activeConversation.id !== undefined) {
                    void loadConversationHistory(
                      activeConversation.clientId,
                      activeConversation.id,
                    );
                  }
                }}
              >
                Retry loading history
              </Button>
            </div>
          ) : activeConversation?.messages.length ? (
            activeConversation.messages.map((message) => (
              <ChatMessageView message={message} key={message.id} />
            ))
          ) : (
            <div className="ai-chat-empty-state">
              <h2>What would you like to explore?</h2>
              <p>
                {activeConversation?.id === undefined
                  ? "Send a message to begin this chat. It will be saved when the message is accepted."
                  : "This chat has no externally retained message history to display."}
              </p>
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
              isDisabled={
                isSending || activeConversation?.historyStatus !== "AVAILABLE"
              }
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
              isDisabled={
                !isSending &&
                (!draft.trim() ||
                  activeConversation?.historyStatus !== "AVAILABLE")
              }
              onPress={() =>
                isSending
                  ? activeRequestRef.current?.abort()
                  : void sendMessage()
              }
            />
          </div>
        </div>
      </section>

      <ModalOverlay
        isOpen={Boolean(conversationToRename)}
        isDismissable
        className="ai-chat-rename-modal-overlay"
        onOpenChange={(isOpen) => {
          if (!isOpen && !isRenaming) {
            setConversationToRename(undefined);
          }
        }}
      >
        <Modal className="ai-chat-rename-modal w-full max-w-md">
          <Dialog aria-label="Rename chat" className="ai-chat-rename-dialog">
            <div className="ai-chat-rename-header">
              <div>
                <h2>Rename chat</h2>
                <p>
                  Use a short name that makes this conversation easy to find.
                </p>
              </div>
              <Button
                aria-label="Close rename chat dialog"
                color="tertiary"
                size="sm"
                iconLeading={XClose}
                isDisabled={isRenaming}
                onPress={() => setConversationToRename(undefined)}
              />
            </div>
            <div className="ai-chat-rename-form">
              <Input
                autoFocus
                label="Chat name"
                name="chatName"
                size="md"
                value={renameTitle}
                isInvalid={Boolean(renameError)}
                hint={renameError}
                maxLength={100}
                onChange={(title) => {
                  setRenameTitle(title);
                  setRenameError(undefined);
                }}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    event.preventDefault();
                    void saveConversationTitle();
                  }
                }}
              />
            </div>
            <div className="ai-chat-rename-actions">
              <Button
                color="tertiary"
                size="sm"
                isDisabled={isRenaming}
                onPress={() => setConversationToRename(undefined)}
              >
                Cancel
              </Button>
              <Button
                color="primary"
                size="sm"
                isLoading={isRenaming}
                onPress={() => void saveConversationTitle()}
              >
                Save name
              </Button>
            </div>
          </Dialog>
        </Modal>
      </ModalOverlay>

      <ConfirmationDialog
        isOpen={Boolean(conversationToDelete)}
        title={`Delete “${conversationToDelete?.title ?? ""}”?`}
        description="This removes the saved chat from Renalo. Externally retained AI history is not deleted from LiteLLM."
        confirmLabel="Delete chat"
        isConfirming={isDeleting}
        onCancel={() => setConversationToDelete(undefined)}
        onConfirm={() => void confirmDeleteConversation()}
      />
    </PageLayout>
  );
}

function ChatMessageView({ message }: { message: ChatMessage }) {
  const isThinking =
    message.author === "Renalo" &&
    message.isStreaming &&
    Boolean(message.thinkingLabel);

  return (
    <article
      className={`ai-chat-message ai-chat-message--${message.author === "You" ? "user" : "assistant"}`}
      data-chat-author={message.author}
    >
      <div className="ai-chat-message-body">
        <div className="ai-chat-message-content">
          {message.author === "Renalo" ? (
            <>
              {assistantItems(message).map((item) => (
                <AssistantStreamItemView
                  item={item}
                  isStreaming={message.isStreaming}
                  key={assistantItemKey(item)}
                />
              ))}
              {isThinking && (
                <div
                  className="ai-chat-tool-activity"
                  data-tool-status="IN_PROGRESS"
                  role="status"
                  aria-label={`${message.thinkingLabel}...`}
                >
                  <span
                    className="ai-chat-tool-activity-dot"
                    aria-hidden="true"
                  />
                  {message.thinkingLabel}
                </div>
              )}
            </>
          ) : (
            message.content
          )}
        </div>
      </div>
    </article>
  );
}

function AssistantStreamItemView({
  item,
  isStreaming,
}: {
  item: AssistantStreamItem;
  isStreaming?: boolean;
}) {
  if (item.type === "chart") {
    return <AiChatChartView chart={item.chart} />;
  }
  if (item.type === "tools") {
    const summary = summarizeToolActivities(item.activities);
    return (
      <div className="ai-chat-tool-activity" data-tool-status={summary.status}>
        <span className="ai-chat-tool-activity-dot" aria-hidden="true" />
        {summary.label}
        {summary.status === "CANCELLED" && (
          <span className="ai-chat-tool-activity-status">· Stopped</span>
        )}
      </div>
    );
  }
  return (
    <Suspense
      fallback={
        <div className="ai-chat-markdown-loading">Formatting response...</div>
      }
    >
      <AiMarkdown isStreaming={isStreaming}>{item.content}</AiMarkdown>
    </Suspense>
  );
}

function assistantItems(message: ChatMessage): AssistantStreamItem[] {
  if (message.streamItems) {
    return message.streamItems;
  }
  return [
    ...(message.charts ?? []).map((chart) => ({
      type: "chart" as const,
      chart,
    })),
    ...(message.content
      ? [
          {
            type: "content" as const,
            id: "history-content",
            content: message.content,
          },
        ]
      : []),
  ];
}

function historyAssistantItems(
  items: AiChatHistoryItem[],
): AssistantStreamItem[] {
  let result: AssistantStreamItem[] = [];
  items.forEach((item, index) => {
    if (item.type === "CONTENT") {
      result.push({
        type: "content",
        id: `history-content-${index}`,
        content: item.content,
      });
      return;
    }
    if (item.type === "CHART") {
      result.push({ type: "chart", chart: item.chart });
      return;
    }
    result = appendToolActivity(result, {
      id: `history-tool-${index}`,
      label: item.label,
      status: "COMPLETED",
    });
  });
  return result;
}

function assistantItemKey(item: AssistantStreamItem) {
  return item.type === "chart" ? `chart-${item.chart.id}` : item.id;
}

function summarizeToolActivities(activities: AiChatToolActivity[]) {
  const counts = new Map<string, number>();
  for (const activity of activities) {
    counts.set(activity.label, (counts.get(activity.label) ?? 0) + 1);
  }
  const label = [...counts]
    .map(([activityLabel, count]) =>
      count > 1 ? `${activityLabel} (${count})` : activityLabel,
    )
    .join(", ");
  const status = activities.some(
    (activity) => activity.status === "IN_PROGRESS",
  )
    ? "IN_PROGRESS"
    : activities.some((activity) => activity.status === "CANCELLED")
      ? "CANCELLED"
      : "COMPLETED";
  return { label, status };
}

function createDraftConversation(clientId: string): Conversation {
  return {
    clientId,
    title: "New chat",
    messages: [],
    historyStatus: "AVAILABLE",
  };
}

function fromPersistedConversation(
  conversation: AiChatConversation,
): Conversation {
  return {
    clientId: `conversation-${conversation.id}`,
    ...conversation,
    messages: [],
    historyStatus: "NOT_LOADED",
  };
}

function appendMessages(
  conversations: Conversation[],
  conversationClientId: string,
  messages: ChatMessage[],
) {
  return conversations.map((conversation) =>
    conversation.clientId === conversationClientId
      ? { ...conversation, messages: [...conversation.messages, ...messages] }
      : conversation,
  );
}

function applyEventToMessage(
  message: ChatMessage,
  event: AiChatStreamEvent,
): ChatMessage {
  switch (event.type) {
    case "assistant.delta":
      return {
        ...message,
        content: message.content + event.text,
        streamItems: appendAssistantContent(
          message.streamItems,
          event.text,
          event.seq,
        ),
        thinkingLabel: undefined,
      };
    case "assistant.chart":
      return {
        ...message,
        charts: [...(message.charts ?? []), event.chart],
        streamItems: [
          ...(message.streamItems ?? []),
          { type: "chart", chart: event.chart },
        ],
        thinkingLabel: undefined,
      };
    case "assistant.thinking":
      return { ...message, thinkingLabel: event.label };
    case "tool.started":
      return {
        ...message,
        thinkingLabel: undefined,
        streamItems: appendToolActivity(message.streamItems, {
          id: event.activityId,
          label: event.label,
          status: "IN_PROGRESS",
        }),
      };
    case "tool.completed":
      return {
        ...message,
        streamItems: message.streamItems?.map((item) =>
          item.type === "tools"
            ? {
                ...item,
                activities: item.activities.map((activity) =>
                  activity.id === event.activityId
                    ? {
                        ...activity,
                        label: event.label,
                        status: event.status,
                      }
                    : activity,
                ),
              }
            : item,
        ),
      };
    case "turn.completed":
    case "turn.error":
      return { ...message, isStreaming: false, thinkingLabel: undefined };
    case "conversation.created":
    case "conversation.updated":
    case "turn.started":
      return message;
  }
}

function finishStreamingMessage(
  conversations: Conversation[],
  conversationClientId: string,
  messageId: number,
  removeIfEmpty: boolean,
) {
  return conversations.map((conversation) => {
    if (conversation.clientId !== conversationClientId) {
      return conversation;
    }
    const message = conversation.messages.find((item) => item.id === messageId);
    if (
      removeIfEmpty &&
      message &&
      !message.content &&
      !message.charts?.length &&
      !message.streamItems?.length
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
              thinkingLabel: undefined,
              streamItems: item.streamItems?.map((streamItem) =>
                streamItem.type === "tools"
                  ? {
                      ...streamItem,
                      activities: streamItem.activities.map((activity) =>
                        activity.status === "IN_PROGRESS"
                          ? { ...activity, status: "CANCELLED" as const }
                          : activity,
                      ),
                    }
                  : streamItem,
              ),
            }
          : item,
      ),
    };
  });
}

function appendAssistantContent(
  items: AssistantStreamItem[] | undefined,
  text: string,
  sequence: number,
): AssistantStreamItem[] {
  const current = items ?? [];
  const last = current.at(-1);
  if (last?.type === "content") {
    return [...current.slice(0, -1), { ...last, content: last.content + text }];
  }
  return [
    ...current,
    { type: "content", id: `content-${sequence}`, content: text },
  ];
}

function appendToolActivity(
  items: AssistantStreamItem[] | undefined,
  activity: AiChatToolActivity,
): AssistantStreamItem[] {
  const current = items ?? [];
  const last = current.at(-1);
  if (last?.type === "tools") {
    return [
      ...current.slice(0, -1),
      { ...last, activities: [...last.activities, activity] },
    ];
  }
  return [
    ...current,
    { type: "tools", id: `tools-${activity.id}`, activities: [activity] },
  ];
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === "AbortError";
}

function getConversationMetadata(event: AiChatStreamEvent) {
  if (
    event.type === "conversation.created" ||
    event.type === "conversation.updated"
  ) {
    return event.conversation;
  }
  return event.type === "turn.completed" ? event.conversation : undefined;
}

function sortConversations(conversations: Conversation[]) {
  return [...conversations].sort((left, right) => {
    if (left.updatedAt && right.updatedAt) {
      return compareConversationsByUpdatedAt(left, right);
    }
    if (left.updatedAt) {
      return -1;
    }
    if (right.updatedAt) {
      return 1;
    }
    return 0;
  });
}

function compareConversationsByUpdatedAt(
  left: Pick<Conversation, "updatedAt">,
  right: Pick<Conversation, "updatedAt">,
) {
  return (right.updatedAt ?? "").localeCompare(left.updatedAt ?? "");
}

const conversationTimeFormatter = new Intl.DateTimeFormat(undefined, {
  month: "short",
  day: "numeric",
  hour: "numeric",
  minute: "2-digit",
});

function formatConversationUpdatedAt(updatedAt: string) {
  return `Updated ${conversationTimeFormatter.format(new Date(updatedAt))}`;
}
