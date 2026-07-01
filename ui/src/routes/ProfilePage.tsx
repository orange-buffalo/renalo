import { type FormEvent, useEffect, useState } from "react";
import {
  ApiError,
  changePassword,
  deletePasskey,
  fetchPasskeys,
  type Passkey,
  registerPasskey,
} from "@/api/auth";
import { PageLayout } from "@/components/PageLayout";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { Button } from "@/components/untitled/base/buttons/button";
import { Input } from "@/components/untitled/base/input/input";

export function ProfilePage() {
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [newPasswordConfirmation, setNewPasswordConfirmation] = useState("");
  const [currentPasswordError, setCurrentPasswordError] = useState<string>();
  const [newPasswordError, setNewPasswordError] = useState<string>();
  const [error, setError] = useState<string>();
  const [success, setSuccess] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [passkeys, setPasskeys] = useState<Passkey[]>([]);
  const [passkeyError, setPasskeyError] = useState<string>();
  const [isLoadingPasskeys, setIsLoadingPasskeys] = useState(true);
  const [isAddingPasskey, setIsAddingPasskey] = useState(false);
  const [deletingPasskeyId, setDeletingPasskeyId] = useState<number>();

  useEffect(() => {
    let isActive = true;

    async function loadPasskeys() {
      setIsLoadingPasskeys(true);
      try {
        const loadedPasskeys = await fetchPasskeys();
        if (isActive) {
          setPasskeys(loadedPasskeys);
          setPasskeyError(undefined);
        }
      } catch {
        if (isActive) {
          setPasskeyError("Passkeys could not be loaded.");
        }
      } finally {
        if (isActive) {
          setIsLoadingPasskeys(false);
        }
      }
    }

    void loadPasskeys();

    return () => {
      isActive = false;
    };
  }, []);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setCurrentPasswordError(undefined);
    setNewPasswordError(undefined);
    setError(undefined);
    setSuccess(false);

    if (!currentPassword) {
      setCurrentPasswordError("Enter your current password.");
      return;
    }
    if (!newPassword) {
      setNewPasswordError("Enter a new password.");
      return;
    }
    if (newPassword !== newPasswordConfirmation) {
      setNewPasswordError("Passwords must match.");
      return;
    }

    setIsSubmitting(true);
    try {
      await changePassword(currentPassword, newPassword);
      setCurrentPassword("");
      setNewPassword("");
      setNewPasswordConfirmation("");
      setSuccess(true);
    } catch (caughtError) {
      if (
        caughtError instanceof ApiError &&
        caughtError.status === 409 &&
        caughtError.code === "CURRENT_PASSWORD_INVALID"
      ) {
        setCurrentPasswordError("Current password is incorrect.");
        return;
      }

      setError("Password could not be changed. Try again in a moment.");
    } finally {
      setIsSubmitting(false);
    }
  }

  async function handleAddPasskey() {
    setPasskeyError(undefined);
    setIsAddingPasskey(true);
    try {
      const passkey = await registerPasskey();
      setPasskeys((currentPasskeys) => [...currentPasskeys, passkey]);
    } catch {
      setPasskeyError("Passkey could not be added. Try again in a moment.");
    } finally {
      setIsAddingPasskey(false);
    }
  }

  async function handleDeletePasskey(passkeyId: number) {
    setPasskeyError(undefined);
    setDeletingPasskeyId(passkeyId);
    try {
      await deletePasskey(passkeyId);
      setPasskeys((currentPasskeys) =>
        currentPasskeys.filter((passkey) => passkey.id !== passkeyId),
      );
    } catch {
      setPasskeyError("Passkey could not be removed. Try again in a moment.");
    } finally {
      setDeletingPasskeyId(undefined);
    }
  }

  return (
    <PageLayout
      title="My profile"
      description="Manage your personal account settings."
    >
      <section className="standard-page-panel profile-panel">
        <form className="profile-form" onSubmit={handleSubmit}>
          <div className="profile-form-heading">
            <h2>Change password</h2>
            <p>Update the password you use to sign in to Renalo.</p>
          </div>

          {success && (
            <Alert
              tone="success"
              title="Password changed"
              className="profile-form-wide"
            >
              <p>Use your new password the next time you sign in.</p>
            </Alert>
          )}
          {error && (
            <Alert tone="error" title={error} className="profile-form-wide" />
          )}

          <Input
            label="Current password"
            name="currentPassword"
            type="password"
            autoComplete="current-password"
            size="md"
            wrapperClassName="profile-password-field"
            value={currentPassword}
            isInvalid={Boolean(currentPasswordError)}
            hint={currentPasswordError}
            onChange={(nextPassword) => {
              setCurrentPassword(nextPassword);
              setCurrentPasswordError(undefined);
              setSuccess(false);
            }}
          />
          <div className="profile-form-spacer" aria-hidden="true" />
          <Input
            label="New password"
            name="newPassword"
            type="password"
            autoComplete="new-password"
            size="md"
            wrapperClassName="profile-password-field"
            value={newPassword}
            isInvalid={Boolean(newPasswordError)}
            hint={newPasswordError}
            onChange={(nextPassword) => {
              setNewPassword(nextPassword);
              setNewPasswordError(undefined);
              setSuccess(false);
            }}
          />
          <Input
            label="Confirm new password"
            name="newPasswordConfirmation"
            type="password"
            autoComplete="new-password"
            size="md"
            wrapperClassName="profile-password-field"
            value={newPasswordConfirmation}
            isInvalid={Boolean(newPasswordError)}
            onChange={(nextPasswordConfirmation) => {
              setNewPasswordConfirmation(nextPasswordConfirmation);
              setNewPasswordError(undefined);
              setSuccess(false);
            }}
          />

          <div className="profile-form-actions">
            <span />
            <Button
              color="primary"
              size="sm"
              type="submit"
              isLoading={isSubmitting}
            >
              Change password
            </Button>
          </div>
        </form>
      </section>

      <section className="standard-page-panel profile-panel profile-passkeys-panel">
        <div className="profile-passkeys-heading">
          <div>
            <h2>Passkeys</h2>
            <p>
              Use this device's secure sign-in method instead of a password.
            </p>
          </div>
          <Button
            color="primary"
            size="sm"
            type="button"
            isLoading={isAddingPasskey}
            onClick={handleAddPasskey}
          >
            Add passkey
          </Button>
        </div>

        {passkeyError && <Alert tone="error" title={passkeyError} />}

        {isLoadingPasskeys ? (
          <p className="profile-passkeys-muted">Loading passkeys...</p>
        ) : passkeys.length === 0 ? (
          <p className="profile-passkeys-muted">
            No passkeys have been added yet.
          </p>
        ) : (
          <div className="profile-passkeys-list">
            {passkeys.map((passkey) => (
              <div className="profile-passkey-row" key={passkey.id}>
                <div>
                  <strong>{passkey.device}</strong>
                  <p>
                    Added {formatPasskeyDate(passkey.createdAt)} · Last used{" "}
                    {passkey.lastUsedAt
                      ? formatPasskeyDate(passkey.lastUsedAt)
                      : "never"}
                  </p>
                </div>
                <Button
                  color="secondary"
                  size="sm"
                  type="button"
                  isLoading={deletingPasskeyId === passkey.id}
                  onClick={() => void handleDeletePasskey(passkey.id)}
                >
                  Remove
                </Button>
              </div>
            ))}
          </div>
        )}
      </section>
    </PageLayout>
  );
}

function formatPasskeyDate(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}
