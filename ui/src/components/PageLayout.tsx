import { BarChartSquare02, Menu02, Users01, X } from "@untitledui/icons";
import type { ReactNode } from "react";
import { useState } from "react";
import { useLocation, useNavigate } from "react-router";
import { useAppState } from "@/AppState";
import type { UserType } from "@/api/auth";
import { NavList } from "@/components/untitled/application/app-navigation/base-components/nav-list";
import type { NavItemType } from "@/components/untitled/application/app-navigation/config";

type PageLayoutProps = {
  eyebrow: string;
  title: string;
  children?: ReactNode;
};

export function PageLayout({ eyebrow, title, children }: PageLayoutProps) {
  const { profile } = useAppState();
  const location = useLocation();
  const navigate = useNavigate();
  const [isMenuOpen, setIsMenuOpen] = useState(false);
  const navigationItems = getNavigationItems(profile?.type);

  function handleNavigate(event: React.MouseEvent, href?: string) {
    if (!href?.startsWith("/")) {
      return;
    }

    event.preventDefault();
    setIsMenuOpen(false);
    navigate(href);
  }

  return (
    <div
      className="standard-page-shell"
      data-menu-open={isMenuOpen || undefined}
    >
      <header className="standard-page-mobile-header">
        <button
          type="button"
          className="standard-page-menu-button"
          aria-label={
            isMenuOpen ? "Close navigation menu" : "Open navigation menu"
          }
          aria-expanded={isMenuOpen}
          aria-controls="standard-page-navigation"
          onClick={() => setIsMenuOpen((current) => !current)}
        >
          {isMenuOpen ? (
            <X aria-hidden="true" />
          ) : (
            <Menu02 aria-hidden="true" />
          )}
        </button>
        <div className="standard-page-mobile-brand">
          <span className="standard-page-logo" aria-hidden="true">
            R
          </span>
          <span>Renalo</span>
        </div>
      </header>

      <aside className="standard-page-sidebar" id="standard-page-navigation">
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
            onNavigate={handleNavigate}
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

function getNavigationItems(userType?: UserType): NavItemType[] {
  if (userType === "ADMIN") {
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
