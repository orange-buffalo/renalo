import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router";
import { useAppState } from "@/AppState";
import { clearAuthToken, createAuthToken, fetchProfile } from "@/api/auth";
import { fetchSystemSettings } from "@/api/system";
import { AnonymousPage } from "@/components/AnonymousPage";
import { showNotification } from "@/components/untitled/application/notifications/notifications";
import { Button } from "@/components/untitled/base/buttons/button";
import { Input } from "@/components/untitled/base/input/input";

export function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { profile, setProfile, setSettings } = useAppState();
  const [error, setError] = useState<string | undefined>();
  const [isLoading, setIsLoading] = useState(false);

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
    if (!profile) {
      return;
    }

    navigate(profile.type === "ADMIN" ? "/user-management" : "/tracking", {
      replace: true,
    });
  }, [navigate, profile]);

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(undefined);
    setIsLoading(true);

    const formData = new FormData(event.currentTarget);
    const username = String(formData.get("username") ?? "");
    const password = String(formData.get("password") ?? "");

    try {
      await createAuthToken(username, password);
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
      setError("Invalid username or password.");
    } finally {
      setIsLoading(false);
    }
  }

  return (
    <AnonymousPage className="anonymous-page-shell--login">
      <section className="login-card" aria-labelledby="login-heading">
        <p className="eyebrow">Budgeting starts here</p>
        <h1 id="login-heading">Sign in to Renalo</h1>
        <p className="intro">Sign in to continue to your budget workspace.</p>
        <form className="login-form" onSubmit={handleSubmit}>
          <Input label="Username" name="username" autoComplete="username" />
          <Input
            label="Password"
            name="password"
            type="password"
            autoComplete="current-password"
            isInvalid={Boolean(error)}
            hint={error}
          />
          <Button color="primary" size="md" type="submit" isLoading={isLoading}>
            Sign in
          </Button>
        </form>
      </section>
    </AnonymousPage>
  );
}
