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

export function LoadingPage() {
  return (
    <AnonymousPage ariaLabel="Loading Renalo">
      <section className="loading-card">
        <div className="loading-brand">
          <span className="loading-logo" aria-hidden="true">
            R
          </span>
          <span>Renalo</span>
        </div>
        <p>Loading your workspace...</p>
      </section>
    </AnonymousPage>
  );
}
