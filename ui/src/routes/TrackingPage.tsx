import { PageLayout } from "@/components/PageLayout";

export function TrackingPage() {
  return (
    <PageLayout eyebrow="Tracking" title="Expense tracking">
      <section className="standard-page-panel">
        <h2>Today&apos;s budget activity</h2>
        <p>
          Track spending, review categories, and keep the budget workspace up to
          date.
        </p>
      </section>
    </PageLayout>
  );
}
