import { ChevronDown, Plus, Send01 } from "@untitledui/icons";
import { useEffect, useRef, useState } from "react";
import { sendAiChatMessage } from "@/api/aiChat";
import { PageLayout } from "@/components/PageLayout";
import { Button } from "@/components/untitled/base/buttons/button";
import { Dropdown } from "@/components/untitled/base/dropdown/dropdown";
import { TextArea } from "@/components/untitled/base/textarea/textarea";

type ChatMessage = {
  id: number;
  author: "You" | "Renalo";
  content: string;
};

type Conversation = {
  id: number;
  title: string;
  messages: ChatMessage[];
};

const initialConversation: Conversation = {
  id: 1,
  title: "Conversation 1",
  messages: [],
};

export function AiChatPage() {
  const feedRef = useRef<HTMLDivElement>(null);
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
    const userMessage: ChatMessage = {
      id: nextMessageId,
      author: "You",
      content,
    };
    setNextMessageId((current) => current + 1);
    setConversations((current) =>
      appendMessage(current, conversationId, userMessage),
    );
    setDraft("");
    setError(undefined);
    setIsSending(true);

    try {
      const response = await sendAiChatMessage(content);
      const assistantMessage: ChatMessage = {
        id: nextMessageId + 1,
        author: "Renalo",
        content: response.content,
      };
      setNextMessageId((current) => current + 1);
      setConversations((current) =>
        appendMessage(current, conversationId, assistantMessage),
      );
    } catch {
      setError(
        "The message could not be sent. Your message remains in this conversation.",
      );
    } finally {
      setIsSending(false);
    }
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
                  <div className="ai-chat-message-content">
                    {message.content}
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
          {isSending && (
            <div className="ai-chat-writing" role="status">
              Renalo is composing a response...
            </div>
          )}
        </div>

        <div className="ai-chat-composer">
          {error && <p className="ai-chat-error">{error}</p>}
          <div className="ai-chat-composer-input">
            <TextArea
              aria-label="Message"
              placeholder="Write a message..."
              rows={2}
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
              aria-label="Send message"
              className="ai-chat-send-button"
              color="tertiary"
              size="sm"
              iconLeading={Send01}
              isDisabled={!draft.trim() || isSending}
              isLoading={isSending}
              onPress={() => void sendMessage()}
            />
          </div>
        </div>
      </section>
    </PageLayout>
  );
}

function appendMessage(
  conversations: Conversation[],
  conversationId: number,
  message: ChatMessage,
) {
  return conversations.map((conversation) =>
    conversation.id === conversationId
      ? { ...conversation, messages: [...conversation.messages, message] }
      : conversation,
  );
}
