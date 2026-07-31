import { code } from "@streamdown/code";
import { math } from "@streamdown/math";
import { mermaid } from "@streamdown/mermaid";
import { Streamdown } from "streamdown";

const plugins = { code, math, mermaid };

type AiMarkdownProps = {
  children: string;
};

export function AiMarkdown({ children }: AiMarkdownProps) {
  return (
    <Streamdown
      className="ai-chat-markdown"
      controls={{
        code: { copy: true, download: false },
        table: { copy: true, download: false, fullscreen: true },
        mermaid: {
          copy: true,
          download: false,
          fullscreen: true,
          panZoom: true,
        },
      }}
      disallowedElements={["img"]}
      lineNumbers={false}
      linkSafety={{ enabled: true }}
      mermaid={{ config: { securityLevel: "strict" } }}
      mode="static"
      plugins={plugins}
      remend={{ linkMode: "text-only" }}
      skipHtml
      urlTransform={safeUrlTransform}
    >
      {children}
    </Streamdown>
  );
}

function safeUrlTransform(url: string) {
  if (url.startsWith("/") || url.startsWith("#")) {
    return url;
  }

  try {
    const parsedUrl = new URL(url);
    return ["https:", "http:", "mailto:"].includes(parsedUrl.protocol)
      ? url
      : null;
  } catch {
    return null;
  }
}
