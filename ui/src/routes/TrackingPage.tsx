import type { Profile } from "@/api/auth";

type TrackingPageProps = {
  profile?: Profile;
};

export function TrackingPage({ profile }: TrackingPageProps) {
  return (
    <main className="app-page">
      <p className="eyebrow">Tracking</p>
      <h1>Expense tracking</h1>
      {profile && <p className="intro">Signed in as {profile.username}</p>}
    </main>
  );
}
