import { type FormEvent, useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router";
import {
  activateAccount,
  fetchAccountActivationStatus,
} from "@/api/accountActivation";
import { AnonymousPage } from "@/components/AnonymousPage";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { Button } from "@/components/untitled/base/buttons/button";
import { Input } from "@/components/untitled/base/input/input";

export function ActivateAccountPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const token = searchParams.get("token") ?? "";
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [passwordConfirmation, setPasswordConfirmation] = useState("");
  const [isLoading, setIsLoading] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isTokenInvalid, setIsTokenInvalid] = useState(false);
  const [passwordError, setPasswordError] = useState<string>();

  useEffect(() => {
    let isCurrentRequest = true;
    setIsLoading(true);
    setIsTokenInvalid(false);

    if (!token) {
      setIsTokenInvalid(true);
      setIsLoading(false);
      return;
    }

    fetchAccountActivationStatus(token)
      .then((status) => {
        if (!isCurrentRequest) {
          return;
        }
        setUsername(status.username);
      })
      .catch(() => {
        if (isCurrentRequest) {
          setIsTokenInvalid(true);
        }
      })
      .finally(() => {
        if (isCurrentRequest) {
          setIsLoading(false);
        }
      });

    return () => {
      isCurrentRequest = false;
    };
  }, [token]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setPasswordError(undefined);

    if (!password) {
      setPasswordError("Enter a password.");
      return;
    }

    if (password !== passwordConfirmation) {
      setPasswordError("Passwords must match.");
      return;
    }

    setIsSubmitting(true);
    try {
      await activateAccount(token, password, passwordConfirmation);
      navigate("/", {
        replace: true,
        state: {
          notification: {
            title: "Login now with your credentials.",
            tone: "info",
          },
        },
      });
    } catch {
      setIsTokenInvalid(true);
    } finally {
      setIsSubmitting(false);
    }
  }

  if (isLoading) {
    return (
      <AnonymousPage ariaLabel="Loading account activation">
        <section className="loading-card">
          <div className="loading-brand">
            <span className="loading-logo" aria-hidden="true">
              R
            </span>
            <span>Renalo</span>
          </div>
          <p>Checking your activation link...</p>
        </section>
      </AnonymousPage>
    );
  }

  if (isTokenInvalid) {
    return (
      <AnonymousPage ariaLabel="Invalid account activation link">
        <section className="activation-error-card">
          <Alert tone="error" title="Activation link expired">
            <p>
              This activation link is unknown or has expired. Contact your
              server administrator to get a new activation link.
            </p>
          </Alert>
        </section>
      </AnonymousPage>
    );
  }

  return (
    <AnonymousPage className="anonymous-page-shell--login">
      <section className="login-card" aria-labelledby="activation-heading">
        <p className="eyebrow">Account activation</p>
        <h1 id="activation-heading">Set your password</h1>
        <p className="intro">
          Create a password to activate your Renalo account and start using your
          workspace.
        </p>
        <form className="login-form" onSubmit={handleSubmit}>
          <Input label="Username" name="username" value={username} isDisabled />
          <Input
            label="Password"
            name="password"
            type="password"
            autoComplete="new-password"
            value={password}
            onChange={(nextPassword) => {
              setPassword(nextPassword);
              setPasswordError(undefined);
            }}
            isInvalid={Boolean(passwordError)}
            hint={passwordError}
          />
          <Input
            label="Confirm password"
            name="passwordConfirmation"
            type="password"
            autoComplete="new-password"
            value={passwordConfirmation}
            onChange={(nextPasswordConfirmation) => {
              setPasswordConfirmation(nextPasswordConfirmation);
              setPasswordError(undefined);
            }}
            isInvalid={Boolean(passwordError)}
          />
          <Button
            color="primary"
            size="md"
            type="submit"
            isLoading={isSubmitting}
          >
            Activate account
          </Button>
        </form>
      </section>
    </AnonymousPage>
  );
}
