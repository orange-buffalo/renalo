import type { Profile } from "@/api/auth";

type UserManagementPageProps = {
  profile?: Profile;
};

export function UserManagementPage({ profile }: UserManagementPageProps) {
  return (
    <main className="app-page">
      <p className="eyebrow">Administration</p>
      <h1>User management</h1>
      {profile && <p className="intro">Signed in as {profile.username}</p>}
    </main>
  );
}
