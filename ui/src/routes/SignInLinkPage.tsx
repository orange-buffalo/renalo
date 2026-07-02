import { useEffect, useRef } from "react";
import { useLocation, useNavigate } from "react-router";
import { useAppState } from "@/AppState";
import {
  clearAuthToken,
  createAuthTokenWithSignInLink,
  fetchProfile,
} from "@/api/auth";
import { fetchSystemSettings } from "@/api/system";
import { LoadingPage } from "@/components/AnonymousPage";

export function SignInLinkPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const { setProfile, setSettings } = useAppState();
  const processedTokenRef = useRef<string | undefined>(undefined);

  useEffect(() => {
    const token = new URLSearchParams(location.search).get("token") ?? "";
    if (processedTokenRef.current === token) {
      return;
    }
    processedTokenRef.current = token;

    async function signInWithLink() {
      if (!token) {
        throw new Error("Sign in link token is missing.");
      }

      await createAuthTokenWithSignInLink(token);
      const [profile, settings] = await Promise.all([
        fetchProfile(),
        fetchSystemSettings(),
      ]);
      setProfile(profile);
      setSettings(settings);
      navigate("/profile?setupPasskey=true", { replace: true });
    }

    signInWithLink().catch(() => {
      clearAuthToken();
      setProfile(undefined);
      setSettings(undefined);
      navigate("/?signInLinkInvalid=true", { replace: true });
    });
  }, [location.search, navigate, setProfile, setSettings]);

  return <LoadingPage />;
}
