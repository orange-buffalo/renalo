import {
  BarChartSquare02,
  LogOut01,
  Menu02,
  Settings01,
  User01,
  Users01,
  X,
} from "@untitledui/icons";
import type { ReactNode } from "react";
import { useState } from "react";
import { Button as AriaButton } from "react-aria-components";
import { useLocation, useNavigate } from "react-router";
import { useAppState } from "@/AppState";
import { clearAuthToken, type UserType } from "@/api/auth";
import { NavList } from "@/components/untitled/application/app-navigation/base-components/nav-list";
import type { NavItemType } from "@/components/untitled/application/app-navigation/config";
import { AvatarLabelGroup } from "@/components/untitled/base/avatar/avatar-label-group";
import { Dropdown } from "@/components/untitled/base/dropdown/dropdown";

type PageLayoutProps = {
  eyebrow: string;
  title: string;
  description?: string;
  titleTrailing?: ReactNode;
  actions?: ReactNode;
  children?: ReactNode;
};

export function PageLayout({
  eyebrow,
  title,
  description,
  titleTrailing,
  actions,
  children,
}: PageLayoutProps) {
  const { profile, setProfile, setSettings } = useAppState();
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

  function handleSignOut() {
    clearAuthToken();
    setProfile(undefined);
    setSettings(undefined);
    setIsMenuOpen(false);
    navigate("/", { replace: true });
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
          {profile ? (
            <Dropdown.Root>
              <AriaButton
                aria-label="Open account menu"
                className="standard-page-account-trigger group"
              >
                <AvatarLabelGroup
                  size="md"
                  title={profile.username}
                  subtitle={profile.type}
                  placeholderIcon={User01}
                  rounded
                />
              </AriaButton>
              <Dropdown.Popover
                placement="top left"
                className="standard-page-account-popover w-80 overflow-hidden rounded-xl"
              >
                <div className="standard-page-account-menu-header">
                  <AvatarLabelGroup
                    size="lg"
                    title={profile.username}
                    subtitle={profile.type}
                    placeholderIcon={User01}
                    rounded
                  />
                </div>
                <Dropdown.Menu selectionMode="none" aria-label="Account menu">
                  <Dropdown.Item
                    label="My Profile"
                    icon={User01}
                    selectionIndicator="none"
                    onAction={() => {
                      setIsMenuOpen(false);
                      navigate("/profile");
                    }}
                  />
                </Dropdown.Menu>
                <Dropdown.Separator />
                <div className="standard-page-account-menu-footer">
                  <button
                    type="button"
                    className="standard-page-sign-out-button"
                    onClick={handleSignOut}
                  >
                    <LogOut01 aria-hidden="true" />
                    Sign out
                  </button>
                </div>
              </Dropdown.Popover>
            </Dropdown.Root>
          ) : (
            "Budget workspace"
          )}
        </footer>
      </aside>

      <div className="standard-page-content">
        <div className="standard-page-surface">
          <header className="standard-page-header">
            <div className="standard-page-heading">
              <p className="eyebrow">{eyebrow}</p>
              <div className="standard-page-title-row">
                <h1>{title}</h1>
                {titleTrailing}
              </div>
              {description && (
                <p className="standard-page-description">{description}</p>
              )}
            </div>
            {actions && <div className="standard-page-actions">{actions}</div>}
          </header>

          <main className="standard-page-main">{children}</main>
        </div>
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
    {
      label: "Settings",
      href: "/settings",
      icon: Settings01,
    },
  ];
}
