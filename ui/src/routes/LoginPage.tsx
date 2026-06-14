import { useState } from "react";
import { useNavigate } from "react-router";
import { createAuthToken, fetchProfile } from "@/api/auth";
import { Button } from "@/components/untitled/base/buttons/button";
import { Input } from "@/components/untitled/base/input/input";

export function LoginPage() {
  const navigate = useNavigate();
  const [error, setError] = useState<string | undefined>();
  const [isLoading, setIsLoading] = useState(false);

  async function handleSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(undefined);
    setIsLoading(true);

    const formData = new FormData(event.currentTarget);
    const username = String(formData.get("username") ?? "");
    const password = String(formData.get("password") ?? "");

    try {
      await createAuthToken(username, password);
      const profile = await fetchProfile();
      navigate(profile.type === "ADMIN" ? "/user-management" : "/tracking", {
        state: { profile },
        replace: true,
      });
    } catch {
      setError("Invalid username or password.");
    } finally {
      setIsLoading(false);
    }
  }

  return (
    <main className="login-shell">
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
          />
          {error && <p className="form-error">{error}</p>}
          <Button color="primary" size="md" type="submit" isLoading={isLoading}>
            Sign in
          </Button>
        </form>
      </section>
    </main>
  );
}
