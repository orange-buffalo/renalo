# Renalo

Renalo is a Micronaut/Kotlin budgeting application scaffold for tracking expenses and income.

## Requirements

- Java 25. Gradle is configured to auto-download a Java 25 toolchain when needed.
- Bun 1.3+
- Docker, only when building or running container images.
- PostgreSQL for normal application runtime.

## Running Locally

- `./gradlew run` starts the app locally.
- `/workspace/renalo-local-state/start-renalo.sh` starts the app against the shared local PostgreSQL instance on port `8484`.

## Database

Default runtime configuration expects PostgreSQL:

- `JDBC_URL`, default `jdbc:postgresql://localhost:5432/renalo`
- `JDBC_USER`, default `renalo`
- `JDBC_PASSWORD`, default `renalo`

On startup, if no admin user exists, the app creates a default admin and logs the generated credentials once.

## Deployment

Deployments must provide a PostgreSQL database through `JDBC_URL`, `JDBC_USER`, and `JDBC_PASSWORD`. Flyway migrations run automatically at startup.

When the first instance starts with an empty database, Renalo creates a default admin account and prints a boxed log entry containing the generated username and password. Capture these credentials from application logs immediately; the generated password is only logged at creation time. If an admin already exists, no password is generated or printed.

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
  ghcr.io/orange-buffalo/renalo:latest
```
