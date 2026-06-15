import type { ReactNode } from "react";
import { createContext, useContext, useEffect, useState } from "react";
import type { Profile } from "@/api/auth";
import { clearAuthToken, fetchProfile, getAuthToken } from "@/api/auth";
import { LoadingPage } from "@/components/AnonymousPage";

type AuthStatus = "checking" | "authenticated" | "anonymous";

type AppState = {
  authStatus: AuthStatus;
  profile?: Profile;
  setProfile: (profile: Profile | undefined) => void;
};

const AppStateContext = createContext<AppState | undefined>(undefined);

export function AppStateProvider({ children }: { children: ReactNode }) {
  const [profile, setProfile] = useState<Profile | undefined>();
  const [authStatus, setAuthStatus] = useState<AuthStatus>("checking");

  useEffect(() => {
    let isActive = true;
    const bootstrapFrame = requestAnimationFrame(() => {
      if (!getAuthToken()) {
        setAuthStatus("anonymous");
        return;
      }

      fetchProfile()
        .then((restoredProfile) => {
          if (!isActive) {
            return;
          }
          setProfile(restoredProfile);
          setAuthStatus("authenticated");
        })
        .catch(() => {
          if (!isActive) {
            return;
          }
          clearAuthToken();
          setProfile(undefined);
          setAuthStatus("anonymous");
        });
    });

    return () => {
      isActive = false;
      cancelAnimationFrame(bootstrapFrame);
    };
  }, []);

  function updateProfile(nextProfile: Profile | undefined) {
    setProfile(nextProfile);
    setAuthStatus(nextProfile ? "authenticated" : "anonymous");
  }

  return (
    <AppStateContext.Provider
      value={{ authStatus, profile, setProfile: updateProfile }}
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
