# AI Chat Design

## Status

This is the living design for adding conversational access to Renalo's financial data. It records the current architectural decisions and constraints, but it is not a fixed roadmap or milestone plan. The feature will be implemented and refined iteratively; this document should change when implementation experience produces a better design.

No AI chat capability is implied to exist until the corresponding application changes are shipped.

## Product Context

Renalo is a private, self-hosted application for an individual, household, or small trusted group. It is not intended to be a public multi-tenant SaaS platform. One deployment-level AI configuration is therefore preferable to provider credentials and billing controls for every Renalo user.

Regular-user data boundaries still apply. Each conversation belongs to one authenticated regular user, and AI tools may only read that user's data. The model never supplies, selects, or overrides the effective user ID.

## Goals

- Let a regular user ask questions about their Renalo data in natural language.
- Support multiple independent conversations per user.
- Keep conversations usable across Renalo restarts without storing chat messages in Renalo's database.
- Support local models and major commercial providers through one application-facing protocol.
- Keep authorization, financial calculations, date semantics, and tool execution inside Renalo.
- Stream answers to the browser and allow cancellation.
- Degrade clearly when the gateway, model, or externally stored conversation is unavailable.

## Non-goals

- Public SaaS tenancy, per-user provider accounts, billing quotas, or a provider marketplace.
- Model-generated SQL, direct database access, or general-purpose filesystem and shell tools.
- Write-capable financial tools in the initial design.
- Treating generated prose as an authoritative financial calculation.
- Guaranteeing that consumer AI subscriptions provide durable API access.
- Persisting a second copy of the chat transcript in Renalo.

## Architecture Decision

Renalo uses **LangChain4j** for JVM-side model interaction and typed tool orchestration, and a separately deployed, pinned **LiteLLM Proxy** as its model gateway.

```text
Browser
  -> authenticated Renalo chat API
  -> Renalo chat orchestration
       -> user-scoped, read-only Renalo tools
       -> LangChain4j Responses API client
  -> LiteLLM Proxy
       -> commercial provider, OpenRouter, Ollama, vLLM, or another configured backend
```

LangChain4j is wired as ordinary Micronaut beans. The experimental Micronaut LangChain4j integration is not required. This keeps dependency alignment explicit and lets Renalo control request context, session locking, error mapping, and streaming.

Renalo targets LiteLLM's OpenAI-compatible Responses API rather than exposing provider-specific integrations. LangChain4j's `OpenAiResponsesChatModel` and `OpenAiResponsesStreamingChatModel` support `previousResponseId`, tools, and response metadata, but this integration is currently experimental. Compatibility with the pinned LangChain4j and LiteLLM versions is therefore a tested application contract, not an assumption.

LiteLLM owns provider credentials, model aliases, routing, and provider-specific translation. Renalo owns the conversation workflow and all domain tools. Internal tools are ordinary Kotlin/Java methods; MCP is unnecessary for them.

## Deployment Configuration

AI configuration is deployment-wide and supplied through environment-backed application configuration. The expected settings are:

- Enabled state.
- LiteLLM base URL.
- LiteLLM API key.
- Stable model alias configured in LiteLLM.

Renalo uses sensible built-in defaults for timeouts and safety limits. These are implementation details rather than deployment configuration unless operational experience establishes a concrete need to expose them.

The LiteLLM key is never returned to the browser or stored in reversible form in Renalo's database. Private-network LiteLLM URLs are valid and expected for self-hosted deployments.

The deployment must pin a tested LiteLLM version. Arbitrary OpenAI-compatible gateways are best-effort because stateful Responses API behavior, streaming tool calls, and error semantics differ. Provider API keys, cloud credentials, gateway accounts, and local models are supported operating models. Consumer subscription connectors are experimental conveniences whose availability and terms can change independently of Renalo.

## Conversation Ownership and Persistence

### Renalo-owned metadata

Renalo stores one lightweight row per conversation. A row is scoped to a regular user and contains only the metadata required to list and resume it, for example:

- Renalo conversation ID.
- Owning `user_id`.
- User-visible title.
- Current external response ID.
- Model alias captured when the conversation starts.
- Creation and last-update timestamps.
- A concurrency version or equivalent locking field.

It does not store user prompts, assistant messages, tool arguments, tool results, or a transcript. The external response ID is opaque server-side data and is not exposed to the browser.

An empty new chat is browser-only. Renalo creates the metadata row when the first nonblank user message is accepted, then identifies the newly persisted conversation in the response stream. This avoids abandoned rows for chats that never contain a turn. The initial title is generic; an AI-generated title derived from the first prompt replaces it asynchronously, and users can explicitly rename persisted conversations. Conversation metadata is touched when each user message is accepted and when each assistant turn completes so recently active conversations sort first.

### Externally owned conversation state

Each successful Responses API call returns a response ID. Renalo supplies the latest ID as `previous_response_id` on the next turn and replaces its stored pointer with the newly completed response ID. The resulting response chain is the external conversation state.

LiteLLM must be configured with durable response/session storage appropriate to the selected providers so this chain survives both Renalo and LiteLLM restarts. For providers bridged through LiteLLM, this can require LiteLLM prompt/response storage and its supporting PostgreSQL and object storage configuration. Provider-native retention alone must not be assumed.

Opening a conversation requires resolving its latest response ID through LiteLLM. Transcript reconstruction follows the externally retained response chain; Renalo does not reconstruct context from financial records or guess missing messages. Before implementation is considered compatible with a model route, tests must prove that the route can:

- Create and retrieve stored responses.
- Continue with `previous_response_id` after Renalo and LiteLLM restarts.
- Reconstruct the ordered user-visible transcript, including turns involving Renalo tool calls.
- Distinguish a missing response from temporary gateway failures.

If the pinned LiteLLM Responses API cannot reconstruct the complete chain for a configured route, that route does not satisfy Renalo's persistent-session contract. A possible later adapter could place LangChain4j memory in an external durable store, but Renalo must not silently fall back to storing messages locally.

### Multiple conversations

A user can create and retain multiple conversation rows. Starting a conversation sends no `previous_response_id`; continuing one uses only that conversation's latest external response ID. Requests for a conversation always query by both Renalo conversation ID and authenticated user ID.

Users cannot share or transfer conversations. Deleting a Renalo user cascades their conversation metadata. Conversation deletion should request deletion of the external response chain when the gateway supports it, then delete the local metadata; failure to remove externally retained data must be visible to the operator rather than silently ignored.

### Missing external state

When LiteLLM cannot resolve the latest response, the UI keeps the conversation in the list and shows its external history as temporarily unavailable. Renalo preserves the external response ID and does not change the conversation to a final or unrecoverable state. The user can retry later, including after a LiteLLM configuration or storage problem is corrected.

A not-found response is not conclusive because a temporary proxy misconfiguration can make durable state invisible. Not-found responses, authentication errors, authorization errors, timeouts, connection failures, rate limits, and gateway `5xx` responses are all recoverable availability failures. They must not clear the external response ID, start a context-free replacement, or otherwise reclassify the conversation.

## Turn Processing

Only one turn may run for a conversation at a time. Renalo serializes turns with a database lock or an equivalent cross-instance mechanism rather than relying on in-process synchronization.

A turn follows this loop:

1. Authenticate the regular user and load the conversation by both conversation ID and user ID.
2. Validate the browser timezone and derive browser-local current-date context in Renalo.
3. Resolve the external response when continuing an existing conversation.
4. Submit the user input, fixed system instructions, tool specifications, model alias, and previous response ID through LangChain4j.
5. Validate each requested tool and its arguments, execute the allowed read-only tool with server-owned user context, and return a bounded structured result.
6. Repeat model and tool exchanges until the model produces a final answer or a built-in safety limit is reached.
7. Stream user-visible answer events to the browser and persist the newest external response pointer as it becomes authoritative.

The external response pointer must be durable before a response is relied on for later tool-loop steps. A process crash can otherwise leave externally accepted state unreachable. In-progress state and restart reconciliation need explicit implementation: after restart, Renalo should inspect the stored external response, safely resume a pending read-only tool exchange when possible, or expose a recoverable interrupted-turn error. It must not invent a completed answer.

Cancellation stops browser streaming and attempts to cancel the active model response. Provider cancellation is not universal, so Renalo must also stop accepting further stream events and tool calls locally. A cancelled or failed response does not replace the last known resumable conversation pointer unless the external protocol requires retaining an intermediate tool-loop response for reconciliation.

## Tool Boundary

The model receives a small fixed allowlist of typed, read-only tools. Initial candidates wrap existing user-scoped services for:

- Account and current-balance summaries.
- Expense and income category totals.
- Expense and income time series.
- Bounded transaction search and detail lookup.
- Net-worth time series.
- Funds-transfer search.
- Active account and category lookup.

Tools never accept `userId`. Renalo injects authenticated user context outside model-visible arguments, using controlled invocation context rather than prompt text. Every tool still validates nullable or malformed arguments, ownership of referenced account/category IDs, date ranges, result limits, and enum values.

Tools return typed values with monetary amounts in exact `Long` minor units plus their ISO currency. Renalo remains authoritative for conversion, overflow behavior, recurrence, future-date exclusion, browser-local today, filtering, and aggregation. The model formats and explains results; it does not recompute authoritative totals from raw rows.

The tool set excludes repositories, `DataSource`, SQL, arbitrary HTTP, filesystem, shell, settings mutation, transaction mutation, and unrestricted history export. Tool output is bounded by date span, rows, buckets, and payload size. The orchestration loop also limits prompt length, output tokens, tool calls, and elapsed time.

## API and Streaming Boundary

The browser talks only to authenticated Renalo endpoints. It never connects directly to LiteLLM and never receives gateway credentials or external response IDs.

The API surface is expected to cover:

- Listing the authenticated user's conversation metadata.
- Lazily creating a conversation with its first accepted turn, then renaming and deleting it.
- Opening a conversation and resolving its external transcript/state.
- Sending one turn as an authenticated stream.
- Cancelling an active turn.

The exact URL and event schema should be chosen with implementation tests. Authenticated `fetch()` streaming is preferred because the existing bearer token and `X-Time-Zone` header cannot be attached by native `EventSource`. Stream events should be structured, versioned, and distinguish text deltas, completion, cancellation, tool activity summaries, and recoverable errors.

Blocking JDBC and long-running model calls must not run on Netty event-loop threads. Streaming work needs a bounded executor, disconnect handling, cancellation propagation, and backpressure or bounded buffering.

## UI Behavior

The first surface is a dedicated authenticated chat page. It provides a conversation list, new-conversation action, transcript, prompt composer, streaming answer state, cancellation, and clear retry behavior. A global panel can be considered later without changing the backend ownership model.

The UI must distinguish:

- Active conversation.
- Empty new conversation.
- Response currently streaming.
- Interrupted but retryable turn.
- Temporarily unavailable gateway or model.
- Temporarily unavailable external conversation history.

Assistant Markdown rendering must not allow raw HTML and must sanitize or constrain links. Tool arguments, raw tool results, gateway identifiers, and hidden reasoning are not rendered as conversation messages. User-visible tool activity can use safe summaries such as “Reviewing expense totals.”

## Security and Privacy

- Controllers require the regular-user role and derive user identity from the authenticated principal.
- Every conversation and tool query is scoped by server-owned `userId`.
- The model cannot choose a user, conversation owner, database query, or executable tool implementation.
- Prompt injection in transaction notes or imported text is untrusted data, not an instruction source.
- Request and response bodies are excluded from ordinary application logs.
- Gateway keys and provider credentials remain server-side secrets.
- Raw financial data sent to LiteLLM may continue to an external provider and may be retained by LiteLLM, object storage, or that provider.
- A local model behind a local LiteLLM deployment is the option for keeping model inputs within operator-controlled infrastructure.
- The operator documentation must state the configured retention, backup, and deletion behavior of LiteLLM's conversation storage.

Renalo's private-deployment positioning reduces the need for SaaS billing and tenant administration, but it does not remove user-data isolation or external disclosure risks.

## Failure Semantics

- Invalid model tool calls return a bounded tool error to the model when repair is safe; repeated invalid calls terminate the turn.
- Arithmetic overflow and domain-service failures propagate as failures and are never replaced with approximate values.
- Missing conversion evidence stays explicitly unavailable.
- Gateway authentication, configuration, and state-lookup failures are operator-actionable and do not alter conversation metadata.
- Rate limits and transient provider failures preserve the conversation pointer and permit retry.
- Context-window exhaustion produces an explicit error until a tested external compaction strategy is available.
- Switching a LiteLLM alias to an incompatible model must not silently reinterpret an existing conversation. The conversation retains its model alias, and route compatibility is validated before continuation.

## Testing Contract

Backend tests must cover complete API responses, regular-user security, cross-user conversation isolation, hidden user context, malformed tool calls, ownership validation, limits, exact amounts, timezones, and all failure mappings.

Gateway contract tests run against the pinned LiteLLM version and representative local and commercial routes. They cover streaming text, streaming tool-call assembly, multi-tool loops, response retrieval, external history reconstruction, process restarts, cancellation, missing responses, provider failures, and pointer reconciliation after interruption.

Playwright coverage verifies multiple conversations, continuing after a Renalo restart, streaming and cancellation, retryable errors, and temporarily unavailable external history. UI traces are reviewed for desktop and mobile behavior. User-facing implementation changes also update the user guide and its documentation screenshots.

Model quality tests use deterministic fixtures and assert tool selection and tool arguments separately from generated prose. They do not require exact natural-language wording from nondeterministic models.

## Evolution Rules

- Prefer the smallest end-to-end implementation that preserves these boundaries.
- Add tools individually with security, domain, and model-behavior tests.
- Keep the application-facing gateway contract narrow even if LiteLLM exposes more features.
- Pin and upgrade LangChain4j and LiteLLM deliberately, rerunning gateway contract tests on every change.
- Record design changes here as the implementation evolves; do not preserve a decision solely because it appeared in the first iteration.

## References

- [LangChain4j tools](https://docs.langchain4j.dev/tutorials/tools/)
- [LangChain4j response streaming](https://docs.langchain4j.dev/tutorials/response-streaming/)
- [LangChain4j chat memory and history distinction](https://docs.langchain4j.dev/tutorials/chat-memory/)
- [LangChain4j OpenAI and Responses API integration](https://docs.langchain4j.dev/integrations/language-models/open-ai/)
- [LiteLLM Responses API and session management](https://docs.litellm.ai/docs/response_api)
- [LiteLLM providers](https://docs.litellm.ai/docs/providers)
