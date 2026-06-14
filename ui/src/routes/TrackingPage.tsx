import type { Profile } from "@/api/auth";
import { PageLayout } from "@/components/PageLayout";

type TrackingPageProps = {
  profile?: Profile;
};

export function TrackingPage({ profile }: TrackingPageProps) {
  return (
    <PageLayout profile={profile} eyebrow="Tracking" title="Expense tracking">
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
