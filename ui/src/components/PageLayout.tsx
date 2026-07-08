import {
  BarChartSquare02,
  CreditCard02,
  DownloadCloud01,
  LogOut01,
  Settings01,
  SwitchHorizontal01,
  TrendUp02,
  User01,
  Users01,
} from "@untitledui/icons";
import { type MouseEvent, type ReactNode, useEffect, useState } from "react";
import { Button as AriaButton } from "react-aria-components";
import { useLocation, useNavigate } from "react-router";
import { useAppState } from "@/AppState";
import { clearAuthToken, type UserType } from "@/api/auth";
import { NavButton } from "@/components/untitled/application/app-navigation/base-components/nav-button";
import type { NavItemType } from "@/components/untitled/application/app-navigation/config";
import { Avatar } from "@/components/untitled/base/avatar/avatar";
import { AvatarLabelGroup } from "@/components/untitled/base/avatar/avatar-label-group";
import { Dropdown } from "@/components/untitled/base/dropdown/dropdown";
import { cx } from "@/utils/cx";
import { logoUrl } from "@/utils/logo";

type PageLayoutProps = {
  title: string;
  description?: string;
  titleTrailing?: ReactNode;
  actions?: ReactNode;
  className?: string;
  children?: ReactNode;
};

export function PageLayout({
  title,
  description,
  titleTrailing,
  actions,
  className,
  children,
}: PageLayoutProps) {
  const { profile, setProfile, setSettings } = useAppState();
  const location = useLocation();
  const navigate = useNavigate();
  const navigationItems = getNavigationItems(profile?.type);

  function handleNavigate(event: MouseEvent, href?: string) {
    if (!href?.startsWith("/")) {
      return;
    }

    event.preventDefault();
    navigate(href);
  }

  function handleSignOut() {
    clearAuthToken();
    setProfile(undefined);
    setSettings(undefined);
    navigate("/", { replace: true });
  }

  const [installPrompt, setInstallPrompt] =
    useState<BeforeInstallPromptEvent | null>(null);

  useEffect(() => {
    function handleBeforeInstallPrompt(event: Event) {
      event.preventDefault();
      setInstallPrompt(event as BeforeInstallPromptEvent);
    }

    globalThis.addEventListener(
      "beforeinstallprompt",
      handleBeforeInstallPrompt,
    );
    return () => {
      globalThis.removeEventListener(
        "beforeinstallprompt",
        handleBeforeInstallPrompt,
      );
    };
  }, []);

  async function handleInstallApp() {
    if (!installPrompt) return;
    installPrompt.prompt();
    const result = await installPrompt.userChoice;
    if (result.outcome === "accepted") {
      setInstallPrompt(null);
    }
  }

  return (
    <div className="standard-page-shell">
      <header className="standard-page-topbar">
        <div className="standard-page-topbar-inner">
          <a
            href="/"
            className="standard-page-brand"
            aria-label="Go to Renalo home"
            onClick={(event) =>
              handleNavigate(event, defaultRouteFor(profile?.type))
            }
          >
            <img src={logoUrl()} alt="" className="standard-page-logo" />
          </a>

          <nav
            aria-label="Main navigation"
            className="standard-page-topbar-nav"
          >
            <ul>
              {navigationItems.map((item) => (
                <li key={item.label}>
                  <NavButton
                    current={isNavigationItemActive(item, location.pathname)}
                    href={item.href}
                    icon={item.icon}
                    label={item.label}
                    className="standard-page-topbar-nav-link"
                    disableTooltip
                    onClick={(event) => handleNavigate(event, item.href)}
                  >
                    <span className="standard-page-topbar-nav-label">
                      {item.label}
                    </span>
                  </NavButton>
                </li>
              ))}
            </ul>
          </nav>

          <div className="standard-page-topbar-actions">
            {profile ? (
              <Dropdown.Root>
                <AriaButton
                  aria-label="Open account menu"
                  className="standard-page-account-trigger group"
                >
                  <Avatar size="sm" placeholderIcon={User01} rounded />
                  <span className="standard-page-account-labels">
                    <span>{profile.username}</span>
                    <span>{profile.type}</span>
                  </span>
                </AriaButton>
                <Dropdown.Popover
                  placement="bottom right"
                  className="standard-page-account-popover w-60 overflow-hidden rounded-xl"
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
                        navigate("/profile");
                      }}
                    />
                    {installPrompt && (
                      <Dropdown.Item
                        label="Install as desktop app"
                        icon={DownloadCloud01}
                        selectionIndicator="none"
                        onAction={handleInstallApp}
                      />
                    )}
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
          </div>
        </div>
      </header>

      <div className="standard-page-content">
        <div className={cx("standard-page-surface", className)}>
          <header className="standard-page-header">
            <div className="standard-page-heading">
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

function isNavigationItemActive(item: NavItemType, activeUrl: string) {
  return Boolean(
    item.href &&
      (item.href === activeUrl || activeUrl.startsWith(`${item.href}/`)),
  );
}

function defaultRouteFor(userType?: UserType) {
  return userType === "ADMIN" ? "/user-management" : "/tracking";
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
      label: "Dashboard",
      href: "/tracking",
      icon: BarChartSquare02,
    },
    {
      label: "Expenses",
      href: "/expenses",
      icon: CreditCard02,
    },
    {
      label: "Incomes",
      href: "/incomes",
      icon: TrendUp02,
    },
    {
      label: "Transfers",
      href: "/transfers",
      icon: SwitchHorizontal01,
    },
    {
      label: "Settings",
      href: "/settings",
      icon: Settings01,
    },
  ];
}
