import { PageLayout } from "@/components/PageLayout";

export function UserManagementPage() {
  return (
    <PageLayout eyebrow="Administration" title="User management">
      <section className="standard-page-panel">
        <h2>Workspace access</h2>
        <p>Manage Renalo users and keep administrative access organized.</p>
      </section>
    </PageLayout>
  );
}
