# Renalo

Renalo is a Micronaut/Kotlin budgeting application scaffold for tracking expenses and income.

User installation and feature documentation is maintained as a Starlight site under [`docs/user`](docs/user). Developer references live under [`docs/developer`](docs/developer).

## Requirements

- Java 25. Gradle is configured to auto-download a Java 25 toolchain when needed.
- Bun 1.3+
- Docker, only when building or running container images.
- PostgreSQL for normal application runtime.

## Database

Default runtime configuration expects PostgreSQL:

- `JDBC_URL`, default `jdbc:postgresql://localhost:5432/renalo`
- `JDBC_USER`, default `renalo`
- `JDBC_PASSWORD`, default `renalo`

On startup, if no admin user exists, the app creates a default admin and logs the generated credentials once.

## Deployment

Deployments must provide a PostgreSQL database through `JDBC_URL`, `JDBC_USER`, and `JDBC_PASSWORD`. Flyway migrations run automatically at startup.

Deployments must also provide `RENALO_JWT_SECRET`, which signs authentication tokens. Generate a strong value with:

```bash
openssl rand -base64 64
```

Keep this value stable across application restarts and across all Renalo instances in the same environment. Changing it invalidates existing signed-in sessions.

Set `RENALO_PUBLIC_URL` to the externally reachable base URL of the deployment, for example `https://renalo.example.com`. Renalo uses it to generate user activation links. The default is `http://localhost:8080`, which is only suitable for local development.

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
  -e RENALO_JWT_SECRET="$(openssl rand -base64 64)" \
  -e RENALO_PUBLIC_URL=http://localhost:8080 \
  ghcr.io/orange-buffalo/renalo:<version>
```

Release documentation is published separately as:

```text
ghcr.io/orange-buffalo/renalo-docs:<version>
```

It serves the static user guide on container port `80`. Build it locally with:

```bash
docker build -t renalo-docs docs/user
```

Set the build argument `RENALO_DOCS_SITE_URL` to the final documentation origin when canonical URLs and a sitemap are required:

```bash
docker build \
  --build-arg RENALO_DOCS_SITE_URL=https://docs.example.com \
  -t renalo-docs docs/user
```

The release workflow reads the optional GitHub Actions repository variable `RENALO_DOCS_SITE_URL` and passes it to the documentation build. Configure that variable to the public documentation origin before publishing the image when search-engine indexing is required.
