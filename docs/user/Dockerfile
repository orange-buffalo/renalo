FROM oven/bun:1.4.4@sha256:1d22548f177f4ff38e0c15baa3c2c46c6c2060ba2d1bc573bb4720b407548f1d AS build
WORKDIR /docs

ARG RENALO_DOCS_SITE_URL
ENV RENALO_DOCS_SITE_URL=${RENALO_DOCS_SITE_URL}

COPY package.json bun.lock ./
RUN bun install --frozen-lockfile

COPY . .
RUN bun run build

FROM caddy:2.11.8-alpine@sha256:f6228111e5fafa1a5238258d0858e103f10599b8d1659a146e7726681a002bb9
COPY Caddyfile /etc/caddy/Caddyfile
COPY --from=build /docs/dist /usr/share/caddy
EXPOSE 80
