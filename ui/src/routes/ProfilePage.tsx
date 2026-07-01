import { type FormEvent, useState } from "react";
import { ApiError, changePassword } from "@/api/auth";
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
    </PageLayout>
  );
}
