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
import { fetchSystemSettings, type SystemSettings } from "@/api/system";
import { LoadingPage } from "@/components/AnonymousPage";
import {
  createDefaultTransactionDateFilter,
  type TransactionDateFilterValue,
} from "@/components/DateRangeFilter";

type AuthStatus = "checking" | "authenticated" | "anonymous";
const accessTokenRefreshLeadTimeMs = 30_000;

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
    let isActive = true;
    async function bootstrapAuthentication(): Promise<
      [Profile, SystemSettings]
    > {
      const token = getAuthToken();
      if (!token) {
        throw new Error("No stored access token");
      }

      const expiresAt = getAuthTokenExpirationTime(token);
      if (
        !expiresAt ||
        expiresAt - Date.now() <= accessTokenRefreshLeadTimeMs
      ) {
        const refreshedToken = await refreshAccessToken();
        if (!refreshedToken) {
          if (expiresAt <= Date.now()) {
            redirectToLoginForExpiredSession();
          }
          throw new Error("Stored access token could not be refreshed");
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

    function scheduleRefresh() {
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
      refreshTimer = window.setTimeout(() => {
        refreshAccessToken()
          .then((refreshedToken) => {
            if (isActive && refreshedToken) {
              scheduleRefresh();
            }
          })
          .catch(() => {
            // The existing access token remains usable until expiry; a later 401 will redirect to login.
          });
      }, refreshDelay);
    }

    scheduleRefresh();

    return () => {
      isActive = false;
      if (refreshTimer !== undefined) {
        window.clearTimeout(refreshTimer);
      }
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
      {authStatus === "checking" ? <LoadingPage /> : children}
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
