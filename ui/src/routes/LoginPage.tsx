import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router";
import { useAppState } from "@/AppState";
import {
  ApiError,
  clearAuthToken,
  createAuthToken,
  createAuthTokenWithPasskey,
  fetchProfile,
} from "@/api/auth";
import { fetchSystemSettings } from "@/api/system";
import { AnonymousPage } from "@/components/AnonymousPage";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { showNotification } from "@/components/untitled/application/notifications/notifications";
import { Button } from "@/components/untitled/base/buttons/button";
import { Checkbox } from "@/components/untitled/base/checkbox/checkbox";
import { Input } from "@/components/untitled/base/input/input";
import { logoExtendedUrl } from "@/utils/logo";

export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { profile, setProfile, setSettings } = useAppState();
  const [passwordError, setPasswordError] = useState<string | undefined>();
  const [passkeyError, setPasskeyError] = useState<string | undefined>();
  const [isLoading, setIsLoading] = useState(false);
  const [isPasskeyLoading, setIsPasskeyLoading] = useState(false);
  const [rememberMe, setRememberMe] = useState(false);
  const sessionExpired =
    new URLSearchParams(location.search).get("sessionExpired") === "true";
  const signInLinkInvalid =
    new URLSearchParams(location.search).get("signInLinkInvalid") === "true";
  const hasAnonymousMessage = sessionExpired || signInLinkInvalid;

  useEffect(() => {
    const state = location.state as {
      notification?: {
        title: string;
        description?: string;
        tone?: "success" | "info";
      };
    } | null;
    if (!state?.notification) {
      return;
    }

    showNotification(state.notification);
    navigate(location.pathname, { replace: true, state: null });
  }, [location.pathname, location.state, navigate]);

  useEffect(() => {
    if (!profile || hasAnonymousMessage) {
      return;
    }

    navigate(profile.type === "ADMIN" ? "/user-management" : "/tracking", {
      replace: true,
    });
  }, [hasAnonymousMessage, navigate, profile]);

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPasswordError(undefined);
    setPasskeyError(undefined);
    setIsLoading(true);

    const formData = new FormData(event.currentTarget);
    const username = String(formData.get("username") ?? "");
    const password = String(formData.get("password") ?? "");

    try {
      await createAuthToken(username, password, rememberMe);
      const [profile, settings] = await Promise.all([
        fetchProfile(),
        fetchSystemSettings(),
      ]);
      setProfile(profile);
      setSettings(settings);
      navigate(profile.type === "ADMIN" ? "/user-management" : "/tracking", {
        replace: true,
      });
    } catch (caughtError) {
      clearAuthToken();
      setProfile(undefined);
      setSettings(undefined);
      setPasswordError(
        caughtError instanceof ApiError &&
          caughtError.code === "PASSWORD_SIGN_IN_DISABLED"
          ? "Password sign-in is disabled for this account. Use a passkey instead."
          : "Invalid username or password.",
      );
    } finally {
      setIsLoading(false);
    }
  }

  async function handlePasskeySignIn() {
    setPasswordError(undefined);
    setPasskeyError(undefined);
    setIsPasskeyLoading(true);

    try {
      await createAuthTokenWithPasskey();
      const [profile, settings] = await Promise.all([
        fetchProfile(),
        fetchSystemSettings(),
      ]);
      setProfile(profile);
      setSettings(settings);
      navigate(profile.type === "ADMIN" ? "/user-management" : "/tracking", {
        replace: true,
      });
    } catch {
      clearAuthToken();
      setProfile(undefined);
      setSettings(undefined);
      setPasskeyError("Passkey sign in failed.");
    } finally {
      setIsPasskeyLoading(false);
    }
  }

  return (
    <AnonymousPage className="anonymous-page-shell--login">
      <section className="login-card" aria-labelledby="login-heading">
        <h1 id="login-heading" className="sr-only">
          Sign in to Renalo
        </h1>
        <img src={logoExtendedUrl()} alt="" className="mx-auto mb-6 size-20" />
        {sessionExpired && (
          <Alert
            tone="brand"
            title="Session expired"
            className="login-info-alert"
          >
            <p>Please sign in again to continue.</p>
          </Alert>
        )}
        {signInLinkInvalid && (
          <Alert
            tone="error"
            title="Sign in link is invalid"
            className="login-info-alert"
          >
            <p>Create a new sign in link from your profile and try again.</p>
          </Alert>
        )}
        <form className="login-form" onSubmit={handleSubmit}>
          <Input label="Username" name="username" autoComplete="username" />
          <Input
            label="Password"
            name="password"
            type="password"
            autoComplete="current-password"
            isInvalid={Boolean(passwordError)}
            hint={passwordError}
          />
          <Checkbox
            label="Remember me"
            name="rememberMe"
            isSelected={rememberMe}
            onChange={setRememberMe}
          />
          <Button color="primary" size="md" type="submit" isLoading={isLoading}>
            Sign in
          </Button>
        </form>
        <div className="login-passkey-divider">or</div>
        <Button
          color="secondary"
          size="md"
          className="w-full"
          type="button"
          isLoading={isPasskeyLoading}
          onClick={handlePasskeySignIn}
        >
          Sign in with passkey
        </Button>
        {passkeyError && <p className="login-passkey-error">{passkeyError}</p>}
      </section>
    </AnonymousPage>
  );
}
