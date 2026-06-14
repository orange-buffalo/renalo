# Agent Instructions

## Project

- Java package root is `io.orangebuffalo.renalo`.
- Gradle group is `io.orange-buffalo`.
- Use Java 25.
- Use `./gradlew build` as the main verification command before finishing changes.

## Backend

- Micronaut serves the Bun-compiled frontend from `classpath:public` as an SPA.
- `index.html` responses must prohibit caching.
- Static assets under `/assets/**` should use long-lived immutable cache headers.
- Tests use Testcontainers PostgreSQL, not H2.
- Database cleanup for tests must truncate application tables before and after each test, excluding Flyway metadata tables.

## Frontend

- Use Bun's bundler. Do not add Vite.
- Keep UI build, lint, and format tasks wired through Gradle.
- Always run `bun run format` from `ui/` after UI source changes, then run Gradle verification.
- Untitled UI React is copy/CLI based, not an npm component-library import.
- Add Untitled UI components with `bunx untitledui@latest add <component> --yes` from `ui/`. Do not hand-write replacements for components that Untitled UI provides.
- Components copied by the CLI should live under `ui/src/components/untitled/` and keep the documented Untitled UI APIs and implementation patterns.
- Button usage should follow the documented shape: `color`, `size`, optional `iconLeading`/`iconTrailing`, `isDisabled`, and `isLoading`.
- Input usage should follow the documented shape: `label`, `name`, `size`, optional `hint`, `isInvalid`, `icon`, `tooltip`, and `shortcut` when needed.
- Required Untitled UI support dependencies include React Aria components, Tailwind utilities, `tailwind-merge`, and `tailwindcss-animate`; keep them in `ui/package.json` when generated components need them.
- Keep custom CSS minimal and scoped. Prefer using Untitled UI copied components over page-specific element selectors.

## Testing

- Java Playwright setup lives in reusable JUnit extension infrastructure under `src/test/java/io/orangebuffalo/renalo/test`.
- Install only the Chromium headless shell for tests with Playwright's `--only-shell` option. Do not use `--with-deps` in the Gradle task because it requires privileged package installation.
