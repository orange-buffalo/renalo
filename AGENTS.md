# Agent Instructions

## Project

- Java package root is `io.orangebuffalo.renalo`.
- Gradle group is `io.orange-buffalo`.
- Use Java 25. Run `source "/usr/local/sdkman/bin/sdkman-init.sh" && sdk env` from the repo before Gradle commands when the shell has not loaded SDKMAN.
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
- Untitled UI React is copy/CLI based. Local components under `ui/src/components/untitled/` should follow the documented Untitled UI APIs instead of inventing incompatible props.
- Button usage should follow the documented shape: `color`, `size`, optional `iconLeading`/`iconTrailing`, `isDisabled`, and `isLoading`.
- Input usage should follow the documented shape: `label`, `name`, `size`, optional `hint`, `isInvalid`, and icon/tooltip-style props when added.
- Keep custom CSS minimal and scoped. Prefer extending local Untitled-style primitives over page-specific element selectors.

## Testing

- Java Playwright setup lives in reusable JUnit extension infrastructure under `src/test/java/io/orangebuffalo/renalo/test`.
- Install only the Chromium headless shell for tests with Playwright's `--only-shell` option. Do not use `--with-deps` in the Gradle task because it requires privileged package installation.
