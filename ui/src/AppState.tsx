import type { ReactNode } from "react";
import { createContext, useContext, useState } from "react";
import type { Profile } from "@/api/auth";

type AppState = {
  profile?: Profile;
  setProfile: (profile: Profile | undefined) => void;
};

const AppStateContext = createContext<AppState | undefined>(undefined);

export function AppStateProvider({ children }: { children: ReactNode }) {
  const [profile, setProfile] = useState<Profile | undefined>();

  return (
    <AppStateContext.Provider value={{ profile, setProfile }}>
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
