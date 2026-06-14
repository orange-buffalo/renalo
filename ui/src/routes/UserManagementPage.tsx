import type { Profile } from "@/api/auth";
import { PageLayout } from "@/components/PageLayout";

type UserManagementPageProps = {
  profile?: Profile;
};

export function UserManagementPage({ profile }: UserManagementPageProps) {
  return (
    <PageLayout
      profile={profile}
      eyebrow="Administration"
      title="User management"
    >
      <section className="standard-page-panel">
        <h2>Workspace access</h2>
        <p>Manage Renalo users and keep administrative access organized.</p>
      </section>
    </PageLayout>
  );
}
