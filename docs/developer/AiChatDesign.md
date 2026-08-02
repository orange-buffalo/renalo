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
- Keep conversations usable across Renalo and LiteLLM restarts through a Renalo-owned append-only event log.
- Support local models and major commercial providers through one application-facing protocol.
- Keep authorization, financial calculations, date semantics, and tool execution inside Renalo.
- Stream answers to the browser and allow cancellation.
- Degrade clearly when the gateway or model is unavailable.

## Non-goals

- Public SaaS tenancy, per-user provider accounts, billing quotas, or a provider marketplace.
- Model-generated SQL, direct database access, or general-purpose filesystem and shell tools.
- Write-capable financial tools in the initial design.
- Treating generated prose as an authoritative financial calculation.
- Guaranteeing that consumer AI subscriptions provide durable API access.
- Maintaining a separate mutable transcript alongside the canonical event log.

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

Renalo targets LiteLLM's OpenAI-compatible Responses API rather than exposing provider-specific integrations. The configured ChatGPT connector does not support durable stored Responses or `previous_response_id`, so Renalo submits complete ordered Responses input on every model step. LangChain4j's Responses integration is currently experimental; compatibility with the pinned LangChain4j and LiteLLM versions is therefore a tested application contract, not an assumption.

LiteLLM owns provider credentials, model aliases, routing, and provider-specific translation. Renalo owns the conversation workflow and all domain tools. Internal tools are ordinary Kotlin/Java methods; MCP is unnecessary for them.

## Deployment Configuration

AI configuration is deployment-wide and supplied through environment-backed application configuration. The expected settings are:

- Enabled state.
- LiteLLM base URL.
- LiteLLM API key.
- Stable model alias configured in LiteLLM.
- Optional maximum context size for that alias, supplied through `RENALO_AI_CHAT_LITELLM_MAX_CONTEXT_TOKENS` when it is known.

Renalo allows three minutes for each model response step and fifteen minutes for a complete turn. The tool-call ceiling remains a secondary runaway-loop guard rather than the normal constraint on a multi-step answer. These are built-in implementation details rather than deployment configuration unless operational experience establishes a concrete need to expose them.

The LiteLLM key is never returned to the browser or stored in reversible form in Renalo's database. Private-network LiteLLM URLs are valid and expected for self-hosted deployments.

The deployment must pin a tested LiteLLM version. Arbitrary OpenAI-compatible gateways are best-effort because stateful Responses API behavior, streaming tool calls, and error semantics differ. Provider API keys, cloud credentials, gateway accounts, and local models are supported operating models. Consumer subscription connectors are experimental conveniences whose availability and terms can change independently of Renalo.

## Conversation Ownership and Persistence

### Renalo-owned state

Renalo stores one metadata row per conversation. A row is scoped to a regular user and contains:

- Renalo conversation ID.
- Owning `user_id`.
- User-visible title.
- Model alias captured when the conversation starts.
- Creation and last-update timestamps.
- A concurrency version or equivalent locking field.

Renalo also stores the canonical conversation state as append-only ordered Responses API items. These include user messages, assistant output messages, function calls, function outputs, and opaque encrypted reasoning items returned by the provider. The raw event log is server-only. Browser history is a projection containing only user input text and assistant output text.

An empty new chat is browser-only. Renalo creates the metadata row when the first nonblank user message is accepted, then identifies the newly persisted conversation in the response stream. This avoids abandoned rows for chats that never contain a turn. The initial title is generic; an AI-generated title derived from the first prompt replaces it asynchronously, and users can explicitly rename persisted conversations. Title generation uses an independent Responses API request with external response storage disabled; it does not become part of the persisted conversation chain. If title generation fails, Renalo reports the failure in server logs, retains the generic title, and continues the accepted chat turn. Conversation metadata is touched when each user message is accepted and when each assistant turn completes so recently active conversations sort first.

### Append-only conversation state

Before invoking the model, Renalo appends the accepted user message. Each completed model step contributes its raw ordered `response.output_item.done` items. Tool execution appends the corresponding `function_call_output` before the next model step. A continuation replays all items in sequence as the next Responses API `input`, with `store=false` and no `previous_response_id`.

Cancellation or failure can leave a persisted function call without its output. Before accepting a continuation, Renalo atomically appends a server-owned interrupted `function_call_output` for each unresolved call and then appends the new user message. This preserves the append-only history, satisfies Responses API replay pairing, and directs the model to fetch fresh data instead of treating an interrupted call as successful.

Renalo also appends an internal turn-metrics item after each completed, failed, or cancelled turn when possible. It records wall-clock duration, the exact sum of reported model-step token totals, and the latest step's token total as a best-effort current-context estimate. Internal metrics items are included in browser history projection but excluded from Responses API replay, so telemetry cannot change model input or break a conversation. Missing provider usage remains unavailable rather than failing the turn.

The event log is authoritative and survives both Renalo and LiteLLM restarts. Opaque reasoning items are preserved exactly rather than interpreted by Renalo because some providers require them when subsequent input includes tool calls or prior reasoning.

Before a model route is considered compatible, tests must prove that it can:

- Accept replayed user, assistant, reasoning, function-call, and function-output items.
- Continue after Renalo and LiteLLM restarts without provider-side stored state.
- Preserve multi-step tool loops and follow-up context.
- Return complete raw output items in the streaming response.

If the pinned LiteLLM Responses API cannot accept exact replay or return complete output items for a configured route, that route does not satisfy Renalo's persistent-session contract.

### Multiple conversations

A user can create and retain multiple conversation rows. Each continuation loads only that conversation's ordered events. Requests for a conversation always query by both Renalo conversation ID and authenticated user ID.

Users cannot share or transfer conversations. Deleting a conversation or user cascades its local event log.

### History projection

Opening a persisted conversation projects its local events into user and assistant messages. Function calls, function outputs, reasoning, identifiers, and provider metadata remain hidden. Because history does not depend on LiteLLM availability, users can read saved conversations while the model gateway is unavailable.

## Turn Processing

Only one turn may run for a conversation at a time. Renalo serializes turns with a database lock or an equivalent cross-instance mechanism rather than relying on in-process synchronization.

A turn follows this loop:

1. Authenticate the regular user and load the conversation by both conversation ID and user ID.
2. Validate the browser timezone and derive browser-local current-date context in Renalo.
3. Append the user message and load the ordered conversation event log.
4. Submit the fixed system instructions, tool specifications, model alias, and replayed event items through LangChain4j.
5. Validate each requested tool and its arguments, execute the allowed read-only tool with server-owned user context, and return a bounded structured result.
6. Repeat model and tool exchanges until the model produces a final answer or a built-in safety limit is reached.
7. Append each model output item before relying on it for the next step and stream user-visible answer events to the browser.

Output items and function outputs must be durable before they are relied on by a later tool-loop step. A process crash may leave an incomplete final turn in the append-only log; restart behavior must expose that interruption and must not invent a completed answer.

Cancellation stops browser streaming and attempts to cancel the active model response. Provider cancellation is not universal, so Renalo must also stop accepting further stream events and tool calls locally. Already accepted event-log items remain durable.

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

Tools return typed values with monetary amounts in exact `Long` minor units plus their ISO currency. Renalo remains authoritative for conversion, overflow behavior, recurrence, future-date exclusion, browser-local today, filtering, and tool-provided aggregation. The model formats and explains results and can reorganize successful tool results into a chart-ready dataset. Model-derived grouping or aggregation is an AI interpretation rather than a new authoritative Renalo calculation.

### Chart presentation

User-guide content and documentation screenshots for charts are intentionally deferred until the chart design is explicitly approved. Do not update them as part of ongoing chart implementation work.

`present_chart` is a general presentation tool rather than a set of source-specific chart commands. After obtaining financial data, the model chooses the grouping, X-axis type, value type, named series, and presentation that best answer the user's question. The supported presentations are line, area, vertical or horizontal bar, pie, donut, and scatter charts; bar and area series can be stacked.

The model supplies a normalized dataset with arbitrary category, ISO date, or numeric X values and exact string Y values. Monetary values remain signed integer minor units with one ISO currency for the chart, while non-monetary values use bounded decimals. This allows data to be grouped by any useful field exposed by a read-only tool and allows multiple measures or groups to be rendered as independent series.

The browser presents artifacts using the same visual language as dashboard charts: visible value axes, exact dark tooltips, hue-distinct series colors before related shades, and full-screen expansion. Horizontal categorical charts grow with their vertical tick count within a bounded embedded height, while full-screen charts use the available viewport. Chart-bearing assistant messages are centered at the standard desktop width and use the full feed width on mobile.

Renalo does not accept executable expressions, SQL, user IDs, provider identifiers, or tool-call identifiers in chart arguments. It validates titles and labels, UUIDs, chart compatibility, decimal and `Long` syntax, currency, series and point uniqueness, nonnegative pie/donut values, and payload cardinality. The chart-ready artifact is persisted in the append-only event log before it is streamed, so the same chart survives history reload without rerunning the model or financial query.

The tool set excludes repositories, `DataSource`, SQL, arbitrary HTTP, filesystem, shell, settings mutation, transaction mutation, and unrestricted history export. Tool output is bounded by date span, rows, buckets, and payload size. The orchestration loop also limits prompt length, output tokens, tool calls, and elapsed time.

## API and Streaming Boundary

The browser talks only to authenticated Renalo endpoints. It never connects directly to LiteLLM and never receives gateway credentials or external response IDs.

The API surface is expected to cover:

- Listing the authenticated user's conversation metadata.
- Lazily creating a conversation with its first accepted turn, then renaming and deleting it.
- Opening a conversation and projecting its local event history.
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

Assistant Markdown rendering must not allow raw HTML and must sanitize or constrain links. Tool arguments, raw tool results, gateway identifiers, and hidden reasoning are not rendered as conversation messages. Running tools and model-wait phases render consistently as pulsing in-progress activities. Renalo emits safe phase labels such as “Thinking” and “Reviewing results” between tool batches; these labels are application state, not model-generated summaries of hidden reasoning. The browser collapses contiguous tool calls into one safe activity summary and counts repeated labels; assistant text or a chart closes that group so later calls appear at the correct point in the response. Conversation history projects persisted function calls into the same ordered content, chart, and Renalo-owned activity items, while discarding raw tool names, arguments, and results. Validated chart artifacts render as structured feed content with an exact accessible data table rather than model-authored HTML.

Each assistant turn shows its duration and total consumed tokens when available. The composer shows the latest context estimate as a circular indicator. When a configured maximum is available, the ring reflects the percentage used; at 75% it changes to a warning state and recommends starting a new chat because the current one may fail when full. The tooltip still shows the current estimate when the maximum is unknown. Renalo deliberately leaves conversation replacement to the user and does not compact chat context.

## Security and Privacy

- Controllers require the regular-user role and derive user identity from the authenticated principal.
- Every conversation and tool query is scoped by server-owned `userId`.
- The model cannot choose a user, conversation owner, database query, or executable tool implementation.
- Prompt injection in transaction notes or imported text is untrusted data, not an instruction source.
- Request and response bodies are excluded from ordinary application logs.
- Gateway keys and provider credentials remain server-side secrets.
- Raw financial data sent to LiteLLM may continue to an external provider and may be retained by LiteLLM, object storage, or that provider.
- A local model behind a local LiteLLM deployment is the option for keeping model inputs within operator-controlled infrastructure.
- Renalo database backups include raw conversation event items; operators must protect and retain them as financial-adjacent private data.

Renalo's private-deployment positioning reduces the need for SaaS billing and tenant administration, but it does not remove user-data isolation or external disclosure risks.

## Failure Semantics

- Invalid model tool calls return a bounded tool error to the model when repair is safe; repeated invalid calls terminate the turn.
- Arithmetic overflow and domain-service failures propagate as failures and are never replaced with approximate values.
- Missing conversion evidence stays explicitly unavailable.
- Gateway authentication and configuration failures are operator-actionable and do not delete persisted events.
- Rate limits and transient provider failures preserve the event log and permit retry.
- Context-window exhaustion produces an explicit error. Renalo warns users as a configured context limit approaches but does not compact or automatically replace conversations.
- Switching a LiteLLM alias to an incompatible model must not silently reinterpret an existing conversation. The conversation retains its model alias, and route compatibility is validated before continuation.

## Testing Contract

Backend tests must cover complete API responses, regular-user security, cross-user conversation isolation, hidden user context, malformed tool calls, ownership validation, limits, exact amounts, timezones, and all failure mappings.

Gateway contract tests run against the pinned LiteLLM version and representative routes. They cover streaming text, raw output-item capture, replayed input, multi-tool loops, follow-up turns, process restarts, cancellation, and provider failures.

Playwright coverage verifies multiple conversations, persisted local history, continuing after a Renalo restart, streaming, cancellation, and retryable errors. UI traces are reviewed for desktop and mobile behavior. User-facing implementation changes also update the user guide and its documentation screenshots.

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
