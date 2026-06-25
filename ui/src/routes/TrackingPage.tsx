import { PageLayout } from "@/components/PageLayout";

export function TrackingPage() {
  return (
    <PageLayout
      title="Dashboard"
      description="Review budget activity, analytics, and account health."
    >
      <section className="standard-page-panel">
        <h2>Budget overview</h2>
        <p>Analytics and tracking summaries will appear here.</p>
      </section>
    </PageLayout>
  );
}
