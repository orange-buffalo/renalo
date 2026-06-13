# Renalo

Renalo is a Micronaut/Kotlin budgeting application scaffold for tracking expenses and income.

## Requirements

- Java 25. Gradle is configured to auto-download a Java 25 toolchain when needed.
- Bun 1.3+
- Docker, only when building or running container images.
- PostgreSQL for normal application runtime.

## Commands

- `./gradlew build` builds the UI with Bun, packages it into the Micronaut app, and runs backend plus UI checks.
- `./gradlew run` starts the app locally.
- `./gradlew test` runs JUnit 6/Jupiter tests, including the Java Playwright SPA test.
- `./gradlew jibDockerBuild` builds a local Docker image named `renalo:latest`.
- `bun install --cwd ui` installs UI dependencies directly.
- `bun run --cwd ui dev` starts the Bun dev server for UI-only work.

## Database

Default runtime configuration expects PostgreSQL:

- `JDBC_URL`, default `jdbc:postgresql://localhost:5432/renalo`
- `JDBC_USER`, default `renalo`
- `JDBC_PASSWORD`, default `renalo`

On startup, if no admin user exists, the app creates a default admin and logs the generated credentials once.

## Docker

Build the image with:

```bash
./gradlew jibDockerBuild
```

Run it with PostgreSQL connection settings:

```bash
docker run --rm -p 8080:8080 \
  -e JDBC_URL=jdbc:postgresql://host.docker.internal:5432/renalo \
  -e JDBC_USER=renalo \
  -e JDBC_PASSWORD=renalo \
  renalo:latest
```

## Agent Notes

- Keep all UI build, lint, and format tasks wired through Gradle.
- Use `./gradlew build` as the main verification command before finishing changes.
- The Micronaut app serves the Bun-compiled frontend from `classpath:public` as an SPA.
- The UI currently renders login controls only; no login API is implemented yet.
