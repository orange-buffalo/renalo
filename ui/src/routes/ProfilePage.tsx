import QRCode from "qrcode";
import { type FormEvent, useEffect, useState } from "react";
import { useLocation } from "react-router";
import { useAppState } from "@/AppState";
import {
  ApiError,
  changePassword,
  createSignInLink,
  deletePasskey,
  disablePasswordSignIn,
  enablePasswordSignIn,
  fetchPasskeys,
  type Passkey,
  registerPasskey,
  type SignInLink,
} from "@/api/auth";
import { ConfirmationDialog } from "@/components/ConfirmationDialog";
import { PageLayout } from "@/components/PageLayout";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { showNotification } from "@/components/untitled/application/notifications/notifications";
import { Button } from "@/components/untitled/base/buttons/button";
import { Input } from "@/components/untitled/base/input/input";

export function ProfilePage() {
  const location = useLocation();
  const { profile, setProfile } = useAppState();
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
  const [signInLink, setSignInLink] = useState<SignInLink>();
  const [signInLinkQrCode, setSignInLinkQrCode] = useState<string>();
  const [signInLinkError, setSignInLinkError] = useState<string>();
  const [isCreatingSignInLink, setIsCreatingSignInLink] = useState(false);
  const [passwordSignInError, setPasswordSignInError] = useState<string>();
  const [isDisablePasswordDialogOpen, setIsDisablePasswordDialogOpen] =
    useState(false);
  const [isUpdatingPasswordSignIn, setIsUpdatingPasswordSignIn] =
    useState(false);

  const showPasskeySetupPrompt =
    new URLSearchParams(location.search).get("setupPasskey") === "true";
  const passwordSignInDisabled = Boolean(profile?.passwordSignInDisabled);
  const canDisablePasswordSignIn = passkeys.length > 0;

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

  useEffect(() => {
    let isActive = true;

    async function generateQrCode() {
      if (!signInLink) {
        setSignInLinkQrCode(undefined);
        return;
      }

      try {
        const qrCode = await QRCode.toDataURL(signInLink.link, {
          errorCorrectionLevel: "M",
          margin: 1,
          width: 192,
        });
        if (isActive) {
          setSignInLinkQrCode(qrCode);
        }
      } catch {
        if (isActive) {
          setSignInLinkQrCode(undefined);
        }
      }
    }

    void generateQrCode();

    return () => {
      isActive = false;
    };
  }, [signInLink]);

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
      setPasskeys((currentPasskeys) => {
        const nextPasskeys = currentPasskeys.filter(
          (passkey) => passkey.id !== passkeyId,
        );
        if (nextPasskeys.length === 0 && profile?.passwordSignInDisabled) {
          setProfile({ ...profile, passwordSignInDisabled: false });
        }
        return nextPasskeys;
      });
    } catch {
      setPasskeyError("Passkey could not be removed. Try again in a moment.");
    } finally {
      setDeletingPasskeyId(undefined);
    }
  }

  async function handleCreateSignInLink() {
    setSignInLinkError(undefined);
    setIsCreatingSignInLink(true);
    try {
      setSignInLink(await createSignInLink());
    } catch {
      setSignInLinkError(
        "Sign in link could not be created. Try again in a moment.",
      );
    } finally {
      setIsCreatingSignInLink(false);
    }
  }

  async function handleCopySignInLink() {
    if (!signInLink) {
      return;
    }

    try {
      await copyText(signInLink.link);
      showNotification({ title: "Sign in link copied." });
    } catch {
      setSignInLinkError(
        "Sign in link could not be copied. Copy it manually instead.",
      );
    }
  }

  async function handleDisablePasswordSignIn() {
    setPasswordSignInError(undefined);
    setIsUpdatingPasswordSignIn(true);
    try {
      setProfile(await disablePasswordSignIn());
      setIsDisablePasswordDialogOpen(false);
      setCurrentPassword("");
      setNewPassword("");
      setNewPasswordConfirmation("");
      setSuccess(false);
    } catch {
      setPasswordSignInError(
        "Password sign in could not be disabled. Try again in a moment.",
      );
    } finally {
      setIsUpdatingPasswordSignIn(false);
    }
  }

  async function handleEnablePasswordSignIn() {
    setPasswordSignInError(undefined);
    setIsUpdatingPasswordSignIn(true);
    try {
      setProfile(await enablePasswordSignIn());
    } catch {
      setPasswordSignInError(
        "Password sign in could not be enabled. Try again in a moment.",
      );
    } finally {
      setIsUpdatingPasswordSignIn(false);
    }
  }

  return (
    <PageLayout
      title="My profile"
      description="Manage your personal account settings."
    >
      <section className="standard-page-panel profile-panel">
        {passwordSignInDisabled ? (
          <div className="profile-form">
            <div className="profile-form-heading">
              <h2>Change password</h2>
              <p>Password sign in is currently disabled for this account.</p>
            </div>
            <Alert
              tone="warning"
              title="Password sign in is disabled"
              className="profile-form-wide"
            >
              <p>Only passkeys can be used to sign in to this account.</p>
            </Alert>
            {passwordSignInError && (
              <Alert
                tone="error"
                title={passwordSignInError}
                className="profile-form-wide"
              />
            )}
            <div className="profile-form-actions">
              <span />
              <Button
                color="secondary"
                size="sm"
                type="button"
                isLoading={isUpdatingPasswordSignIn}
                onClick={handleEnablePasswordSignIn}
              >
                Enable password login
              </Button>
            </div>
          </div>
        ) : (
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
            {passwordSignInError && (
              <Alert
                tone="error"
                title={passwordSignInError}
                className="profile-form-wide"
              />
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
              <span
                className="profile-disable-password-wrapper"
                title={
                  canDisablePasswordSignIn
                    ? undefined
                    : "you must setup at least one passkey to disable password singins"
                }
              >
                <Button
                  color="link-destructive"
                  size="sm"
                  type="button"
                  isDisabled={!canDisablePasswordSignIn}
                  onClick={() => setIsDisablePasswordDialogOpen(true)}
                >
                  disable passwork sing in
                </Button>
              </span>
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
        )}
      </section>

      <ConfirmationDialog
        isOpen={isDisablePasswordDialogOpen}
        title="Disable password sign in?"
        description="if you disable, you can only sing in with a passkey. if passkey is lost, you need to request your administrator to reset you signing method"
        confirmLabel="Disable password sign in"
        isConfirming={isUpdatingPasswordSignIn}
        onCancel={() => setIsDisablePasswordDialogOpen(false)}
        onConfirm={() => void handleDisablePasswordSignIn()}
      />

      <section className="standard-page-panel profile-panel profile-sign-in-link-panel">
        <div className="profile-passkeys-heading">
          <div>
            <h2>Create sign in link</h2>
            <p>
              Create a short-lived link to sign in on another device and set up
              a passkey there. Links expire after 5 minutes.
            </p>
          </div>
          <Button
            color="primary"
            size="sm"
            type="button"
            isLoading={isCreatingSignInLink}
            onClick={handleCreateSignInLink}
          >
            Create link
          </Button>
        </div>

        {signInLinkError && <Alert tone="error" title={signInLinkError} />}

        {signInLink && (
          <div>
            <p className="profile-passkeys-muted">
              This link expires {formatPasskeyDate(signInLink.expiresAt)}.
            </p>
            <div className="activation-link-row profile-sign-in-link-row">
              <input
                readOnly
                value={signInLink.link}
                aria-label="Sign in link"
                onClick={() => void handleCopySignInLink()}
              />
              <Button
                color="secondary"
                size="sm"
                type="button"
                onClick={handleCopySignInLink}
              >
                Copy link
              </Button>
            </div>
            {signInLinkQrCode && (
              <div className="profile-sign-in-link-qr">
                <img src={signInLinkQrCode} alt="Sign in link QR code" />
              </div>
            )}
          </div>
        )}
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

        {showPasskeySetupPrompt && (
          <Alert tone="brand" title="Set up a passkey on this device">
            <p>
              You are signed in with a link. Add a passkey so you can sign in
              from this device next time.
            </p>
          </Alert>
        )}

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

async function copyText(value: string) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(value);
    return;
  }

  const textarea = document.createElement("textarea");
  textarea.value = value;
  textarea.style.position = "fixed";
  textarea.style.opacity = "0";
  document.body.append(textarea);
  textarea.select();
  try {
    document.execCommand("copy");
  } finally {
    textarea.remove();
  }
}
