# Agent Instructions

## Project

- Java package root is `io.orangebuffalo.renalo`.
- Gradle group is `io.orange-buffalo`.
- Use Java 25.
- Do not run the full test suite by default. Verify changes with the smallest relevant Gradle `test` selection.

## Backend

- Micronaut serves the Bun-compiled frontend from `classpath:public` as an SPA.
- `index.html` responses must prohibit caching.
- Static assets under `/assets/**` should use long-lived immutable cache headers.
- Tests use Testcontainers PostgreSQL, not H2.
- Database cleanup for tests must truncate application tables before and after each test, excluding Flyway metadata tables.
- Every API change must be fully covered with tests, including security verification, business flow coverage, and relevant edge cases.

## Frontend

- Use Bun's bundler. Do not add Vite.
- Keep UI build, lint, and format tasks wired through Gradle.
- Always run `bun run format` from `ui/` after UI source changes, then run targeted Gradle verification.
- Untitled UI React is copy/CLI based, not an npm component-library import.
- Add Untitled UI components with `bunx untitledui@latest add <component> --yes` from `ui/`. Do not hand-write replacements for components that Untitled UI provides.
- Components copied by the CLI should live under `ui/src/components/untitled/` and keep the documented Untitled UI APIs and implementation patterns.
- Button usage should follow the documented shape: `color`, `size`, optional `iconLeading`/`iconTrailing`, `isDisabled`, and `isLoading`.
- Input usage should follow the documented shape: `label`, `name`, `size`, optional `hint`, `isInvalid`, `icon`, `tooltip`, and `shortcut` when needed.
- Required Untitled UI support dependencies include React Aria components, Tailwind utilities, `tailwind-merge`, and `tailwindcss-animate`; keep them in `ui/package.json` when generated components need them.
- Keep custom CSS minimal and scoped. Prefer using Untitled UI copied components over page-specific element selectors.
- Functional UI changes must be covered with Playwright tests. Do not add Playwright assertions solely for visual styling; use trace screenshots for visual review instead.
- UI changes must be checked with the Playwright traces produced by the relevant Playwright test task; inspect the trace screenshots for visual regressions such as broken layout, missing styling, overlap, clipping, poor spacing, or inconsistent Untitled UI styling.
- When UI trace screenshots are inspected, include the screenshot file paths in the final response so the user can review the same evidence.

## Testing

- Java Playwright setup lives in reusable JUnit extension infrastructure under `src/test/java/io/orangebuffalo/renalo/test`.
- Run targeted tests with Gradle's `test` task and `--tests`. Use class filters for related test classes, or method filters for narrowly scoped fixes.
- Example API verification: `./gradlew test --tests 'io.orangebuffalo.renalo.AuthApiTest'`.
- Example UI verification: `./gradlew test --tests 'io.orangebuffalo.renalo.LoginPagePlaywrightTest'`.
- Example combined verification for auth UI/API changes: `./gradlew test --tests 'io.orangebuffalo.renalo.AuthApiTest' --tests 'io.orangebuffalo.renalo.LoginPagePlaywrightTest'`.
- Do not run `./gradlew build` or the full test suite unless explicitly requested, preparing a release, validating broad cross-cutting changes, or targeted tests cannot cover the risk.
- Install only the Chromium headless shell for tests with Playwright's `--only-shell` option. Do not use `--with-deps` in the Gradle task because it requires privileged package installation.
- Playwright tests always save traces with screenshots under `build/playwright-traces/`; CI uploads those traces when the build fails.
- When troubleshooting a Playwright failure, open the relevant trace with `bunx playwright show-trace build/playwright-traces/<trace>.zip` and inspect the DOM snapshot, network requests/responses, console output, and screenshots before changing code.
- Use trace screenshots as visual evidence for UI work, even when tests pass; verify styling consistency, responsive layout, and absence of visual glitches before finishing frontend changes.
- Include inspected screenshot file paths in the final response after UI work.
