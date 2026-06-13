const server = Bun.serve({
  port: Number(process.env.PORT ?? 5173),
  async fetch(request) {
    const url = new URL(request.url);

    if (url.pathname === "/src/styles.css") {
      return new Response(Bun.file("src/styles.css"), {
        headers: { "content-type": "text/css" },
      });
    }

    if (url.pathname === "/src/main.tsx") {
      const result = await Bun.build({
        entrypoints: ["src/main.tsx"],
        target: "browser",
      });

      if (!result.success) {
        return new Response(result.logs.map((log) => log.message).join("\n"), {
          status: 500,
          headers: { "content-type": "text/plain" },
        });
      }

      return new Response(await result.outputs[0].text(), {
        headers: { "content-type": "application/javascript" },
      });
    }

    return new Response(Bun.file("index.html"), {
      headers: { "content-type": "text/html" },
    });
  },
});

console.info(
  `Renalo UI dev server listening on http://localhost:${server.port}`,
);
