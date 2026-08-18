import type { ReactNode } from "react";
import { createContext, useContext, useEffect, useState } from "react";
import type { Profile } from "@/api/auth";
import {
  clearAuthToken,
  fetchProfile,
  getAuthToken,
  getAuthTokenExpirationTime,
  redirectToLoginForExpiredSession,
  refreshAccessToken,
} from "@/api/auth";
import { isAccessTokenDueForRefresh } from "@/api/client";
import { fetchSystemSettings, type SystemSettings } from "@/api/system";
import {
  createDefaultTransactionDateFilter,
  type TransactionDateFilterValue,
} from "@/components/DateRangeFilter";

type AuthStatus = "checking" | "authenticated" | "anonymous";
const accessTokenRefreshLeadTimeMs = 30_000;
// Timers stall while the device sleeps or the tab is frozen, so the wall clock
// is polled as a safety net for the precisely scheduled refresh.
const accessTokenWatchdogIntervalMs = 30_000;
const failedRefreshRetryDelayMs = 5_000;
const maxFailedRefreshRetryDelayMs = 60_000;

type AppState = {
  authStatus: AuthStatus;
  profile?: Profile;
  settings?: SystemSettings;
  transactionDateFilter: TransactionDateFilterValue;
  setProfile: (profile: Profile | undefined) => void;
  setSettings: (settings: SystemSettings | undefined) => void;
  setTransactionDateFilter: (filter: TransactionDateFilterValue) => void;
};

const AppStateContext = createContext<AppState | undefined>(undefined);

export function AppStateProvider({ children }: { children: ReactNode }) {
  const [profile, setProfile] = useState<Profile | undefined>();
  const [settings, setSettings] = useState<SystemSettings | undefined>();
  const [transactionDateFilter, setTransactionDateFilter] =
    useState<TransactionDateFilterValue>(() =>
      createDefaultTransactionDateFilter(new Date()),
    );
  const [authStatus, setAuthStatus] = useState<AuthStatus>("checking");

  useEffect(() => {
    if (authStatus !== "checking") {
      const loader = document.getElementById("app-loader");
      if (loader) {
        loader.classList.add("app-loader--fade-out");
        loader.addEventListener("transitionend", () => loader.remove(), {
          once: true,
        });
      }
    }
  }, [authStatus]);

  useEffect(() => {
    let isActive = true;
    async function bootstrapAuthentication(): Promise<
      [Profile, SystemSettings]
    > {
      const token = getAuthToken();
      if (!token) {
        throw new Error("No stored access token");
      }

      const expiresAt = getAuthTokenExpirationTime(token);
      if (isAccessTokenDueForRefresh(token)) {
        const refreshedToken = await refreshAccessToken();
        if (!refreshedToken) {
          if (!expiresAt || expiresAt <= Date.now()) {
            clearAuthToken();
            redirectToLoginForExpiredSession();
            throw new Error(
              "Stored access token expired and could not be refreshed",
            );
          }
        }
      }

      return Promise.all([fetchProfile(), fetchSystemSettings()]);
    }

    const bootstrapFrame = requestAnimationFrame(() => {
      bootstrapAuthentication()
        .then(([restoredProfile, restoredSettings]) => {
          if (!isActive) {
            return;
          }
          setProfile(restoredProfile);
          setSettings(restoredSettings);
          setAuthStatus("authenticated");
        })
        .catch(() => {
          if (!isActive) {
            return;
          }
          clearAuthToken();
          setProfile(undefined);
          setSettings(undefined);
          setAuthStatus("anonymous");
        });
    });

    return () => {
      isActive = false;
      cancelAnimationFrame(bootstrapFrame);
    };
  }, []);

  useEffect(() => {
    if (authStatus !== "authenticated") {
      return;
    }

    let isActive = true;
    let refreshTimer: number | undefined;
    let consecutiveRefreshFailures = 0;
    let isRefreshInFlight = false;
    let isSessionEndScheduled = false;

    function clearRefreshTimer() {
      if (refreshTimer !== undefined) {
        window.clearTimeout(refreshTimer);
        refreshTimer = undefined;
      }
    }

    function scheduleRefresh() {
      clearRefreshTimer();
      const token = getAuthToken();
      if (!token) {
        return;
      }

      const expiresAt = getAuthTokenExpirationTime(token);
      if (!expiresAt) {
        return;
      }

      const refreshDelay = Math.max(
        expiresAt - Date.now() - accessTokenRefreshLeadTimeMs,
        0,
      );
      refreshTimer = window.setTimeout(
        () => runRefresh(token, expiresAt),
        refreshDelay,
      );
    }

    function runRefresh(tokenBeingRefreshed: string, expiresAt: number) {
      clearRefreshTimer();
      isRefreshInFlight = true;
      refreshAccessToken()
        .then((refreshedToken) => {
          isRefreshInFlight = false;
          if (!isActive) {
            return;
          }
          consecutiveRefreshFailures = 0;
          if (refreshedToken) {
            scheduleRefresh();
            return;
          }

          // The server explicitly reports there is no session to extend.
          scheduleExpirationRedirect(tokenBeingRefreshed, expiresAt);
        })
        .catch(() => {
          isRefreshInFlight = false;
          if (!isActive) {
            return;
          }
          // A failed refresh is usually transient (the network is not up yet
          // after a device wake), so keep retrying instead of ending a session
          // the remember-me token can still restore.
          consecutiveRefreshFailures += 1;
          clearRefreshTimer();
          refreshTimer = window.setTimeout(
            () => runRefresh(tokenBeingRefreshed, expiresAt),
            Math.min(
              failedRefreshRetryDelayMs * consecutiveRefreshFailures,
              maxFailedRefreshRetryDelayMs,
            ),
          );
        });
    }

    function scheduleExpirationRedirect(
      tokenBeingRefreshed: string,
      expiresAt: number,
    ) {
      if (!isActive) {
        return;
      }

      clearRefreshTimer();
      isSessionEndScheduled = true;
      refreshTimer = window.setTimeout(
        () => {
          if (
            isActive &&
            getAuthToken() === tokenBeingRefreshed &&
            expiresAt <= Date.now()
          ) {
            clearAuthToken();
            redirectToLoginForExpiredSession();
          }
        },
        Math.max(expiresAt - Date.now(), 0),
      );
    }

    function refreshIfDue() {
      if (!isActive || isRefreshInFlight || isSessionEndScheduled) {
        return;
      }

      const token = getAuthToken();
      if (!token || !isAccessTokenDueForRefresh(token)) {
        return;
      }

      runRefresh(token, getAuthTokenExpirationTime(token) ?? Date.now());
    }

    function handleVisibilityChange() {
      if (document.visibilityState === "visible") {
        refreshIfDue();
      }
    }

    scheduleRefresh();
    const watchdog = window.setInterval(
      refreshIfDue,
      accessTokenWatchdogIntervalMs,
    );
    document.addEventListener("visibilitychange", handleVisibilityChange);
    window.addEventListener("focus", refreshIfDue);
    window.addEventListener("online", refreshIfDue);

    return () => {
      isActive = false;
      clearRefreshTimer();
      window.clearInterval(watchdog);
      document.removeEventListener("visibilitychange", handleVisibilityChange);
      window.removeEventListener("focus", refreshIfDue);
      window.removeEventListener("online", refreshIfDue);
    };
  }, [authStatus]);

  function updateProfile(nextProfile: Profile | undefined) {
    setProfile(nextProfile);
    setAuthStatus(nextProfile ? "authenticated" : "anonymous");
  }

  return (
    <AppStateContext.Provider
      value={{
        authStatus,
        profile,
        settings,
        transactionDateFilter,
        setProfile: updateProfile,
        setSettings,
        setTransactionDateFilter,
      }}
    >
      {children}
    </AppStateContext.Provider>
  );
}

export function useAppState() {
  const state = useContext(AppStateContext);
  if (!state) {
    throw new Error("useAppState must be used inside AppStateProvider");
  }
  return state;
}
