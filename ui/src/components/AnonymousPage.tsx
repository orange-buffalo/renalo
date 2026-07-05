import type { ReactNode } from "react";

type AnonymousPageProps = {
  ariaLabel?: string;
  children: ReactNode;
  className?: string;
};

export function AnonymousPage({
  ariaLabel,
  children,
  className,
}: AnonymousPageProps) {
  return (
    <main
      className={["anonymous-page-shell", className].filter(Boolean).join(" ")}
      aria-label={ariaLabel}
    >
      {children}
    </main>
  );
}
