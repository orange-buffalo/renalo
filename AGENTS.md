# Agent Instructions

## Project

- Java package root is `io.orangebuffalo.renalo`.
- Gradle group is `io.orange-buffalo`.
- Use Java 25.
- Do not run the full test suite by default. Verify changes with the smallest relevant Gradle `test` selection.
- Keep this `AGENTS.md` file up to date while working. Add durable, cross-task knowledge such as general approach, architecture decisions, shared UI patterns, testing conventions, and recurring implementation rules. Do not store task-specific notes, temporary findings, or one-off context here.

## Git

- When a commit is requested, use a Conventional Commit message, for example `feat: add user management table` or `fix: correct auth bootstrap loading state`.
- When the user requests changes, then provides feedback, commit the already-completed changes before starting the feedback changes unless the user explicitly says not to. Default flow: user requests work, agent implements it, user provides feedback, agent commits completed work, then agent addresses the feedback.
- Do not commit newly written code in the same turn it is produced. Leave fresh changes uncommitted for user review, then commit those reviewed changes on the next turn before starting additional feedback work, unless the user explicitly asks to commit immediately.

## Backend

- Micronaut serves the Bun-compiled frontend from `classpath:public` as an SPA.
- Do not silently swallow exceptions. Broad catches are only acceptable at explicit process boundaries where the design requires continuing, and they must log/report the failure and preserve visibility. Prefer precise database operations, validation, or propagated failures over catch-based workarounds.
- Budget-tracking data is always regular-user-bound. `TrackingAccount` owns the account currency and initial balance used by later incomes, expenses, transfers, and analytics settings.
- Income and expense rows share the type-discriminated `Transaction` backend model and transaction-oriented API; expense and income pages use shared transaction UI components configured with type-specific labels, routes, category loaders, and `TransactionType` filters.
- Transaction and funds-transfer overview date filtering is app-global in-memory UI state and must be applied through API/database query parameters, not by client-side filtering of already-loaded rows.
- Expense dates are date-only values (`LocalDate` / ISO `YYYY-MM-DD`), not timestamps.
- Recurrence schedule calculation and display formatting must stay in shared code under `io.orangebuffalo.renalo.recurrence`, not in expense- or income-specific services.
- Generated recurring expenses are regular `Transaction` rows with type `EXPENSE`, linked to a recurring rule by `recurringRuleId` and `recurringInstanceDate`; there is no separate occurrence model.
- Recurring expense generation must only run from explicit write/job boundaries, not from read-only list/detail flows.
- Scoped recurring expense edits/deletes are based on `recurringInstanceDate`, not the current date.
- User-edited generated expenses use `recurringLocked = true`; scheduled generation must preserve locked rows unless an explicit scoped edit/delete targets them.
- A regular user must always have at least one `TrackingAccount`; new and migrated users get a default `Main` account in `AUD` with zero initial balance.
- Income and expense categories are regular-user-bound; new and migrated users get default `General` income and expense categories.
- Archived income and expense categories remain visible in Settings when explicitly requested but must be excluded from transaction category dropdowns and transaction category filters.
- Exactly one `TrackingAccount` per regular user must have `isDefault = true`; changing the default means nominating another account, not unchecking the current default.
- Archived `TrackingAccount` rows remain visible in Settings when explicitly requested but must be excluded from dashboard summaries, transaction account dropdowns, and transaction account filters.
- Money is stored in integer minor units for the account currency, for example `12345` for `USD 123.45`; respect each ISO currency's default fraction digits when formatting/parsing.
- Funds transfers are regular-user-bound records linking source and target `TrackingAccount` rows with separate source and target amount minor units and a date. They do not support recurrence unless explicitly requested.
- Toshl imports create missing accounts and categories, store imported income/expense rows as regular `Transaction` records with metadata `source: toshl`, and reconcile Toshl `Transfer` category row pairs into `FundsTransfer` records by date, original row amount, and original row currency while excluding same-account pairs. Income/expense duplicate detection uses type, date, amount, and final notes/description. Missing account currencies must be inferred from non-transfer income/expense rows only, not transfer rows. For saved transfers, use each side's original row amount when it matches that account's currency; when the transfer row currency differs from the account currency, use Toshl's main/default-currency amount only if the main/default currency matches that account currency, otherwise mark the transfer unmatched. Unreconciled transfer rows must be reported as import warnings instead of imported as transactions, and every processed row should appear in the downloadable processing report with its original line number and status/reason.
- `index.html` responses must prohibit caching.
- Static assets under `/assets/**` should use long-lived immutable cache headers.
- Frontend build outputs for all cacheable assets, including CSS, must be fingerprinted before they are referenced by `index.html`.
- Tests use Testcontainers PostgreSQL, not H2.
- Tests run with `TestTimeProvider.DEFAULT_TIME`; Playwright pages must centrally move browser time to the same instant and let the clock run normally.
- Database cleanup for tests must truncate application tables before and after each test, excluding Flyway metadata tables.
- Every API change must be fully covered with tests, including security verification, business flow coverage, and relevant edge cases.
- Remember-me authentication uses an opaque random token stored in the `renalo.rememberMe` HttpOnly cookie and persisted as a hashed `RememberMeToken` row linked to a user with device, creation time, and last-used time. Access-token refresh goes through `/api/refresh-access-token`, which returns a nullable token body and must validate the cookie token against current persisted token and user state before issuing a new access token.
- Passkeys use Yubico WebAuthn server support with persistent `PasskeyCredential` rows and short-lived `PasskeyChallenge` rows. Passkey login should issue the same JWT access tokens as password login through `AccessTokenService`; WebAuthn option JSON must be serialized as plain maps rather than Jackson `JsonNode` values so Micronaut returns browser-ready `publicKey` payloads.
- Api logging filter (`ApiLoggingFilter`) runs as an `HttpServerFilter` at order `49000`, after `SecurityFilter` (order `39000`). Filter ordering requires implementing `Ordered` via `override fun getOrder(): Int`, not the `@Order` annotation. Authentication is accessed via `request.getAttribute(HttpAttributes.PRINCIPAL, Authentication::class.java)`.
- Account adjustments (`AccountAdjustment`) are manual balance corrections linked to a `TrackingAccount` and user. They are stored in a separate `account_adjustments` table and factored into balance through `AccountAdjustmentService`. Adjustments affect `DashboardService` total balance and the adjustments page's current balance. Each adjustment has a dedicated `date` field (Instant, populated by the caller, not auto-generated) used for display; UI-created adjustments set `date = timeProvider.now()`, Toshl import adjustments set `date` from the CSV row's date at start of UTC day.
- Micronaut/Jackson excludes empty arrays from JSON responses by default. Data classes with `List` fields that must always appear (even empty) need `@field:JsonInclude(JsonInclude.Include.ALWAYS)` on the field, matching the codebase pattern used in `AuthController` and `ToshlImportService`.
- Passkey login refresh token: `User.issueRefreshTokenOnPasskeyLogin` (default `true`) controls whether a remember-me cookie is issued during passkey authentication. `PasskeyService.finishAuthentication()` returns `PasskeyAuthenticationResult` with `issueRefreshToken` and `userId`. `PasskeyController` creates the cookie using the same logic as `AuthController` (opaque random token, SHA-256 hashed, persisted as `RememberMeToken`). Toggle endpoints: `POST /api/profile/enable-passkey-refresh-token` and `POST /api/profile/disable-passkey-refresh-token`. Exposed as `issueRefreshTokenOnPasskeyLogin` in `ProfileResponse`.

## Frontend

- Use Bun's bundler. Do not add Vite.
- Keep UI build, lint, and format tasks wired through Gradle.
- Always run `bun run format` from `ui/` after UI source changes, then run targeted Gradle verification.
- Untitled UI React is copy/CLI based, not an npm component-library import.
- Use Untitled UI components for UI implementation unless explicitly requested otherwise. Do not hand-write replacements for components that Untitled UI provides.
- Before building custom UI, check the Untitled UI component library website for an existing component. Use pages such as `https://www.untitledui.com/react/components/tables`, `https://www.untitledui.com/react/components/pagination`, or the component sidebar at `https://www.untitledui.com/react/components` to identify the component name and documented API.
- Add Untitled UI components with `bunx untitledui@latest add <component> --yes` from `ui/`. Use the CLI component name from the docs, for example `table` or `pagination`.
- Components copied by the CLI should live under `ui/src/components/untitled/` and keep the documented Untitled UI APIs and implementation patterns.
- Button usage should follow the documented shape: `color`, `size`, optional `iconLeading`/`iconTrailing`, `isDisabled`, and `isLoading`.
- Input usage should follow the documented shape: `label`, `name`, `size`, optional `hint`, `isInvalid`, `icon`, `tooltip`, and `shortcut` when needed.
- New standard forms must use the full `standard-page-surface` content width, matching table widths. Do not cap standard forms with narrow `max-width` values. Use a full-width `standard-page-panel` section, a two-column desktop grid, single-column mobile layout, consistent 28px/32px field gaps, and footer actions split with cancel/back on the left and primary save/create on the right. Always preserve the two-column desktop grid, even when a form has only one input; add an empty second-column spacer rather than widening the single input.
- Standard text inputs and dropdowns should be capped at 300px wide via shared components or reusable styles; textareas should keep their full available form width unless explicitly requested otherwise. Money inputs are capped at 200px wide.
- Forms that load existing records or required reference data must keep the form surface in place and block it with the shared `FormLoadingOverlay` using the Untitled line spinner without text.
- Empty table states must use the shared `TableEmptyState` pattern with the search icon card and bold empty-state copy, not plain loading-style text.
- Table loading states must use the shared `TableLoadingState` spinner with centered padding, not inline loading text.
- Table row action groups must use the shared `TableRowActions` helpers so edit/delete icon colors and spacing stay consistent.
- For searchable selects where search is part of the closed field, use documented Untitled `Select.ComboBox` and `Select.Item`; for dropdowns where search appears only after opening, follow the Untitled dropdown `Search simple` pattern with a regular trigger and search input inside the popover.
- Required Untitled UI support dependencies include React Aria components, Tailwind utilities, `tailwind-merge`, and `tailwindcss-animate`; keep them in `ui/package.json` when generated components need them.
- Keep custom CSS minimal and scoped. Prefer using Untitled UI copied components over page-specific element selectors.
- Standard authenticated pages should put page-level descriptions, counters/badges, and primary actions in `PageLayout`, not inside table panels or forms.
- Standard authenticated pages use a single page header level in `PageLayout`; do not add eyebrow/kicker text above the page title.
- Standard authenticated page content and the topbar must share the same 1020px maximum content width, and the sticky topbar must be opaque so scrolling content cannot show through it.
- Table surfaces should use the shared Untitled table/card border treatment so the panel outline is consistent across all current and future tables.
- Functional UI changes must be covered with Playwright tests. Do not add Playwright assertions solely for visual styling; use trace screenshots for visual review instead.
- Maintain one Playwright test class per page or route-level surface, for example `LoginPagePlaywrightTest` for login flows and `UserManagementPagePlaywrightTest` for user-management page flows.
- Do not add permanent mobile-specific Playwright tests unless mobile behavior is the core functional contract being changed. Temporary mobile Playwright checks may be used for verification, but remove them before finishing unless they are necessary regression coverage.
- UI changes must be checked with the Playwright traces produced by the relevant Playwright test task; inspect the trace screenshots for visual regressions such as broken layout, missing styling, overlap, clipping, poor spacing, or inconsistent Untitled UI styling.
- When UI trace screenshots are inspected, extract the reviewed screenshots into the project `build/` directory and include those `build/` paths in the final response so the user can review the same evidence.

## Testing

- Java Playwright setup lives in reusable JUnit extension infrastructure under `src/test/java/io/orangebuffalo/renalo/test`.
- Use Kotest assertions in tests except for Playwright locator assertions, where Playwright assertions should be used. Prefer extension-function call notation, for example `actual.shouldBe(expected)`, instead of infix/postfix notation.
- API tests must assert complete JSON response bodies with Kotest JSON assertions instead of checking individual JSON fragments. Status-only assertions are acceptable for responses without JSON bodies.
- Playwright tests must use full-content assertions for composite UI surfaces such as tables, lists, and cards. Extract the rendered content into data objects and assert the complete collection, for example `actualRows.shouldContainExactly(UserRow("name", "type", "actions"))`; partial assertions are acceptable only when a test is focused on one dedicated aspect. Wrap extracted-content assertions in asynchronous polling such as Kotest `eventually` or Awaitility `await().pollInSameThread().untilAsserted()` so UI updates are synchronized.
- Run targeted tests with Gradle's `test` task and `--tests`. Use class filters for related test classes, or method filters for narrowly scoped fixes.
- Example API verification: `./gradlew test --tests 'io.orangebuffalo.renalo.AuthApiTest'`.
- Example UI verification: `./gradlew test --tests 'io.orangebuffalo.renalo.LoginPagePlaywrightTest'`.
- Example combined verification for auth UI/API changes: `./gradlew test --tests 'io.orangebuffalo.renalo.AuthApiTest' --tests 'io.orangebuffalo.renalo.LoginPagePlaywrightTest'`.
- Do not run `./gradlew build` or the full test suite unless explicitly requested, preparing a release, validating broad cross-cutting changes, or targeted tests cannot cover the risk.
- Install only the Chromium headless shell for tests with Playwright's `--only-shell` option. Do not use `--with-deps` in the Gradle task because it requires privileged package installation.
- Playwright tests always save traces with screenshots under `build/playwright-traces/`; CI uploads those traces when the build fails.
- When troubleshooting a Playwright failure, open the relevant trace with `bunx playwright show-trace build/playwright-traces/<trace>.zip` and inspect the DOM snapshot, network requests/responses, console output, and screenshots before changing code.
- Use trace screenshots as visual evidence for UI work, even when tests pass; verify styling consistency, responsive layout, and absence of visual glitches before finishing frontend changes.
- Extract trace screenshots with `mkdir -p "build/trace-screenshots/<review-name>" && unzip -o "build/playwright-traces/<trace>.zip" "resources/page*.jpeg" -d "build/trace-screenshots/<review-name>"` from the repository root. For multiple page traces, use one subdirectory per page, for example `mkdir -p "build/trace-screenshots/user-edit-review" && unzip -o "build/playwright-traces/UserManagementPagePlaywrightTest-managesUsersFromAdminPage.zip" "resources/page*.jpeg" -d "build/trace-screenshots/user-edit-review/user-management" && unzip -o "build/playwright-traces/EditUserPagePlaywrightTest-updatesUsernameFromEditUserPage.zip" "resources/page*.jpeg" -d "build/trace-screenshots/user-edit-review/edit-user"`.
- Include inspected screenshot paths from the project `build/` directory in the final response after UI work.
