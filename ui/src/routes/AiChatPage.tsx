import { MessageChatCircle, Plus, Send01, Stars02 } from "@untitledui/icons";
import { useState } from "react";
import { sendAiChatMessage } from "@/api/aiChat";
import { PageLayout } from "@/components/PageLayout";
import { Avatar } from "@/components/untitled/base/avatar/avatar";
import { Button } from "@/components/untitled/base/buttons/button";
import { Select } from "@/components/untitled/base/select/select";
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
  const conversationItems = conversations.map((conversation) => ({
    id: String(conversation.id),
    label: conversation.title,
  }));

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
          <div className="ai-chat-conversation-select">
            <Select
              aria-label="Current conversation"
              size="md"
              icon={MessageChatCircle}
              items={conversationItems}
              selectedKey={String(activeConversationId)}
              isDisabled={isSending}
              onSelectionChange={(key) => {
                setActiveConversationId(Number(key));
                setDraft("");
                setError(undefined);
              }}
            >
              {(item) => <Select.Item id={item.id}>{item.label}</Select.Item>}
            </Select>
          </div>
          <Button
            color="secondary"
            size="md"
            iconLeading={Plus}
            isDisabled={isSending}
            onPress={createConversation}
          >
            New conversation
          </Button>
        </div>

        <div
          className="ai-chat-feed"
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
                <Avatar
                  size="sm"
                  initials={message.author === "You" ? "Y" : undefined}
                  placeholderIcon={
                    message.author === "Renalo" ? Stars02 : undefined
                  }
                  rounded
                />
                <div className="ai-chat-message-body">
                  <span className="ai-chat-message-author">
                    {message.author}
                  </span>
                  <div className="ai-chat-message-content">
                    {message.content}
                  </div>
                </div>
              </article>
            ))
          ) : (
            <div className="ai-chat-empty-state">
              <div className="ai-chat-empty-icon">
                <Stars02 aria-hidden="true" />
              </div>
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
          <div className="ai-chat-composer-row">
            <TextArea
              aria-label="Message"
              className="flex-1"
              placeholder="Write a message..."
              rows={3}
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
              color="primary"
              size="lg"
              iconLeading={Send01}
              isDisabled={!draft.trim() || isSending}
              isLoading={isSending}
              onPress={() => void sendMessage()}
            />
          </div>
          <p className="ai-chat-composer-hint">
            Enter to send, Shift + Enter for a new line
          </p>
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
