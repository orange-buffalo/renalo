# Development Guide

## Technology Stack

Renalo uses Kotlin and Micronaut on Java 25, PostgreSQL for persistence, React 19 for the browser application, Bun for frontend tooling, Gradle for the integrated build, and Playwright for browser tests. The production application image is assembled with Jib.

## Prerequisites

- Java 25. Gradle can provision the configured Java toolchain automatically.
- Bun 1.3 or newer.
- Docker for Testcontainers and container-image builds.
- PostgreSQL when running the application outside tests.

## Local Application Configuration

The development defaults expect PostgreSQL at `jdbc:postgresql://localhost:5432/renalo` with username and password `renalo`. Override them as needed:

```bash
export JDBC_URL=jdbc:postgresql://localhost:5432/renalo
export JDBC_USER=renalo
export JDBC_PASSWORD=renalo
export RENALO_JWT_SECRET="$(openssl rand -base64 64)"
export RENALO_PUBLIC_URL=http://localhost:8080
```

Run the application with:

```bash
./gradlew run
```

Flyway applies database migrations at startup. An empty database receives a generated administrator account; this behavior is described for operators in the [first sign-in guide](https://renalo-docs.orange-buffalo.io/getting-started/first-run/).

## Build and Test

The integrated Gradle build installs and compiles the UI, runs frontend unit checks, starts Testcontainers PostgreSQL for integration tests, and runs Playwright browser coverage:

```bash
./gradlew build
```

Prefer the smallest relevant test selection while developing:

```bash
./gradlew test --tests 'io.orangebuffalo.renalo.AuthApiTest'
./gradlew test --tests 'io.orangebuffalo.renalo.LoginPagePlaywrightTest'
```

The reusable Playwright infrastructure stores traces under `build/playwright-traces/`.

## Frontend Development

Frontend sources live under `ui/`. Gradle remains the authoritative integrated build, while the direct Bun commands are useful for focused UI work:

```bash
cd ui
bun install --frozen-lockfile
bun run format
bun run check
bun run test
bun run build
```

The frontend is bundled with Bun and copied into the Micronaut application as static resources. Do not add Vite.

## User Documentation

The Starlight source is under `docs/user/`. Verify it with:

```bash
cd docs/user
bun install --frozen-lockfile
bun run check
bun run build
```

The official build uses `https://renalo-docs.orange-buffalo.io/` as its canonical origin. Set `RENALO_DOCS_SITE_URL` only when building for another origin.

Regenerate the tracked desktop and mobile feature-tour images with:

```bash
RENALO_DOCUMENTATION_SCREENSHOTS=true \
  ./gradlew test \
  --tests 'io.orangebuffalo.renalo.DocumentationScreenshotsPlaywrightTest'
```

Generated files are written to `build/documentation-screenshots/` and must be copied into `docs/user/src/assets/screenshots/` before committing an intentional documentation update.

## Container Images

Build the application image in the local Docker daemon with:

```bash
./gradlew jibDockerBuild
```

Build the static documentation image with:

```bash
docker build -t renalo-docs docs/user
```

Release images are published separately as:

```text
ghcr.io/orange-buffalo/renalo:<version>
ghcr.io/orange-buffalo/renalo-docs:<version>
```

## Versioning and Releases

Project versions are derived from Git history by the Gradle semver plugin. Inspect the current version with:

```bash
./gradlew -q printVersion
```

Release tags use `v<version>`, while container tags use the numeric version without the leading `v`. The maintainer release workflow is documented in [`.agents/skills/renalo-release/SKILL.md`](../../.agents/skills/renalo-release/SKILL.md).
