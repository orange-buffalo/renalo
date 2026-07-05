import { useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router";
import { useAppState } from "@/AppState";
import {
  clearAuthToken,
  createAuthTokenWithSignInLink,
  fetchProfile,
} from "@/api/auth";
import { fetchSystemSettings } from "@/api/system";
import { AnonymousPage } from "@/components/AnonymousPage";
import { LoadingIndicator } from "@/components/untitled/application/loading-indicator/loading-indicator";
import { logoExtendedUrl } from "@/utils/logo";

export function SignInLinkPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const { setProfile, setSettings } = useAppState();
  const [isLoading, setIsLoading] = useState(true);
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
      setIsLoading(false);
      navigate("/?signInLinkInvalid=true", { replace: true });
    });
  }, [location.search, navigate, setProfile, setSettings]);

  if (!isLoading) {
    return null;
  }

  return (
    <AnonymousPage
      ariaLabel="Verifying sign in link"
      className="anonymous-page-shell--login"
    >
      <section
        className="login-card"
        aria-labelledby="sign-in-link-loading-heading"
      >
        <h1 id="sign-in-link-loading-heading" className="sr-only">
          Verifying sign in link
        </h1>
        <div className="flex flex-col items-center gap-6 py-8">
          <img src={logoExtendedUrl()} alt="" className="size-20" />
          <LoadingIndicator
            type="line-simple"
            size="md"
            label="Verifying sign in link..."
          />
        </div>
      </section>
    </AnonymousPage>
  );
}
