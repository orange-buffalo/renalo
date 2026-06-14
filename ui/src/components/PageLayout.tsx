import { BarChartSquare02, Users01 } from "@untitledui/icons";
import type { ReactNode } from "react";
import { useLocation } from "react-router";
import type { Profile } from "@/api/auth";
import { NavList } from "@/components/untitled/application/app-navigation/base-components/nav-list";
import type { NavItemType } from "@/components/untitled/application/app-navigation/config";

type PageLayoutProps = {
  profile?: Profile;
  eyebrow: string;
  title: string;
  children?: ReactNode;
};

export function PageLayout({
  profile,
  eyebrow,
  title,
  children,
}: PageLayoutProps) {
  const location = useLocation();
  const navigationItems = getNavigationItems(profile);

  return (
    <div className="standard-page-shell">
      <aside className="standard-page-sidebar">
        <div className="standard-page-brand">
          <span className="standard-page-logo" aria-hidden="true">
            R
          </span>
          <span>Renalo</span>
        </div>

        <nav aria-label="Main navigation">
          <NavList
            activeUrl={location.pathname}
            className="standard-page-nav"
            items={navigationItems}
          />
        </nav>

        <footer className="standard-page-sidebar-footer">
          {profile
            ? `${profile.username} · ${profile.type}`
            : "Budget workspace"}
        </footer>
      </aside>

      <div className="standard-page-content">
        <header className="standard-page-header">
          <div>
            <p className="eyebrow">{eyebrow}</p>
            <h1>{title}</h1>
          </div>
          {profile && (
            <p className="standard-page-user">
              Signed in as {profile.username}
            </p>
          )}
        </header>

        <main className="standard-page-main">{children}</main>
      </div>
    </div>
  );
}

function getNavigationItems(profile?: Profile): NavItemType[] {
  if (profile?.type === "ADMIN") {
    return [
      {
        label: "User management",
        href: "/user-management",
        icon: Users01,
      },
    ];
  }

  return [
    {
      label: "Tracking",
      href: "/tracking",
      icon: BarChartSquare02,
    },
  ];
}
