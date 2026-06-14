import { Button } from "../components/untitled/Button.tsx";
import { TextField } from "../components/untitled/TextField.tsx";

export function LoginPage() {
  return (
    <main className="login-shell">
      <section className="login-card" aria-labelledby="login-heading">
        <p className="eyebrow">Budgeting starts here</p>
        <h1 id="login-heading">Sign in to Renalo</h1>
        <p className="intro">
          A small workspace for tracking expenses and income is taking shape.
          Sign-in behavior will be wired next.
        </p>
        <form className="login-form">
          <TextField label="Username" name="username" autoComplete="username" />
          <TextField
            label="Password"
            name="password"
            type="password"
            autoComplete="current-password"
          />
          <Button color="primary" size="md" type="button">
            Sign in
          </Button>
        </form>
      </section>
    </main>
  );
}
