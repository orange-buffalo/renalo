import { type FormEvent, useEffect, useState } from "react";
import { useLocation, useNavigate, useParams } from "react-router";
import { useAppState } from "@/AppState";
import type { UserType } from "@/api/auth";
import { ApiError, apiRequest } from "@/api/client";
import { PageLayout } from "@/components/PageLayout";
import {
  Dialog,
  Modal,
  ModalOverlay,
} from "@/components/untitled/application/modals/modal";
import { BadgeWithDot } from "@/components/untitled/base/badges/badges";
import { Button } from "@/components/untitled/base/buttons/button";
import { Input } from "@/components/untitled/base/input/input";

const userTypeLabels: Record<UserType, string> = {
  USER: "User",
  ADMIN: "Admin",
};

type ManagedUser = {
  id: number;
  username: string;
  type: UserType;
  currentUser: boolean;
  active: boolean;
  activationToken?: ActivationToken | null;
};

type ActivationToken = {
  token: string;
  expiresAt: string;
};

export function EditUserPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { userId } = useParams();
  const { settings } = useAppState();
  const [user, setUser] = useState<ManagedUser>();
  const [username, setUsername] = useState("");
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [isRegenerating, setIsRegenerating] = useState(false);
  const [error, setError] = useState<string>();
  const [usernameError, setUsernameError] = useState<string>();
  const [isRegenerateConfirmationOpen, setIsRegenerateConfirmationOpen] =
    useState(false);
  const [notification, setNotification] = useState<string>();

  useEffect(() => {
    const state = location.state as { notification?: string } | null;
    if (!state?.notification) {
      return;
    }

    setNotification(state.notification);
    navigate(location.pathname, { replace: true, state: null });
  }, [location.pathname, location.state, navigate]);

  useEffect(() => {
    if (!notification) {
      return;
    }

    const timeout = window.setTimeout(() => setNotification(undefined), 20_000);
    return () => window.clearTimeout(timeout);
  }, [notification]);

  useEffect(() => {
    let isCurrentRequest = true;
    setIsLoading(true);
    setError(undefined);

    apiRequest<ManagedUser>(`/api/users/${userId}`)
      .then((loadedUser) => {
        if (!isCurrentRequest) {
          return;
        }
        setUser(loadedUser);
        setUsername(loadedUser.username);
      })
      .catch(() => {
        if (isCurrentRequest) {
          setError("User could not be loaded. Try again in a moment.");
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
  }, [userId]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!user) {
      return;
    }

    const trimmedUsername = username.trim();
    if (!trimmedUsername) {
      setUsernameError("Enter a username.");
      return;
    }

    setIsSaving(true);
    setError(undefined);
    setUsernameError(undefined);

    try {
      const updatedUser = await apiRequest<ManagedUser>(
        `/api/users/${user.id}`,
        {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ username: trimmedUsername }),
        },
      );
      setUser(updatedUser);
      setUsername(updatedUser.username);
      navigate("/user-management", {
        state: { notification: "User changes saved." },
      });
    } catch (caughtError) {
      if (
        caughtError instanceof ApiError &&
        caughtError.status === 409 &&
        caughtError.code === "USERNAME_EXISTS"
      ) {
        setUsernameError("A user with this username already exists.");
        return;
      }

      setError("User could not be saved. Try again in a moment.");
    } finally {
      setIsSaving(false);
    }
  }

  async function handleCopyActivationLink() {
    const activationUrl = getActivationUrl(user, settings?.publicUrl);
    if (!activationUrl) {
      return;
    }

    try {
      await copyText(activationUrl);
      setNotification("Activation link copied.");
    } catch {
      setError(
        "Activation link could not be copied. Copy it manually instead.",
      );
    }
  }

  async function handleRegenerateActivationLink() {
    if (!user) {
      return;
    }

    setIsRegenerating(true);
    setError(undefined);

    try {
      const updatedUser = await apiRequest<ManagedUser>(
        `/api/users/${user.id}/activation-token`,
        { method: "POST" },
      );
      setUser(updatedUser);
      setNotification("Activation link regenerated.");
      setIsRegenerateConfirmationOpen(false);
    } catch {
      setError(
        "Activation link could not be regenerated. Try again in a moment.",
      );
    } finally {
      setIsRegenerating(false);
    }
  }

  const title = user ? `Edit ${user.username}` : "Edit user";
  const statusBadge = user ? (
    <span data-testid="user-status-badge">
      <BadgeWithDot color={user.active ? "success" : "gray"} size="sm">
        {user.active ? "Active" : "Inactive"}
      </BadgeWithDot>
    </span>
  ) : undefined;

  return (
    <PageLayout
      eyebrow="Administration"
      title={title}
      titleTrailing={statusBadge}
      description="Update the username. User type is shown for reference and cannot be changed."
      actions={
        user && !user.active ? (
          <Button
            color="secondary-destructive"
            size="sm"
            onPress={() => setIsRegenerateConfirmationOpen(true)}
          >
            Regenerate activation link
          </Button>
        ) : undefined
      }
    >
      <section className="standard-page-panel edit-user-panel">
        {notification && (
          <div className="renalo-notification" role="status">
            {notification}
          </div>
        )}

        {isLoading ? (
          <p className="user-management-message">Loading user...</p>
        ) : user ? (
          <form className="edit-user-form" onSubmit={handleSubmit}>
            <ActivationAlert
              user={user}
              publicUrl={settings?.publicUrl}
              onCopyActivationLink={handleCopyActivationLink}
            />

            <Input
              label="Username"
              name="username"
              size="md"
              value={username}
              onChange={(nextUsername) => {
                setUsername(nextUsername);
                setUsernameError(undefined);
              }}
              isRequired
              validationBehavior="aria"
              isInvalid={Boolean(usernameError)}
              hint={usernameError}
            />
            <Input
              label="Type"
              name="type"
              size="md"
              value={userTypeLabels[user.type]}
              isDisabled
            />

            {error && (
              <p
                className="edit-user-form-error user-management-error"
                role="alert"
              >
                {error}
              </p>
            )}

            <div className="edit-user-actions">
              <Button
                color="tertiary"
                size="sm"
                onPress={() => navigate("/user-management")}
                isDisabled={isSaving}
              >
                Back
              </Button>
              <Button
                color="primary"
                size="sm"
                type="submit"
                isLoading={isSaving}
                isDisabled={username.trim() === user.username}
              >
                Save changes
              </Button>
            </div>
          </form>
        ) : (
          <p
            className="user-management-message user-management-error"
            role="alert"
          >
            {error ?? "User could not be loaded. Try again in a moment."}
          </p>
        )}

        <ModalOverlay
          isOpen={isRegenerateConfirmationOpen}
          isDismissable
          className={(state) => (state.isExiting ? "hidden" : "")}
          onOpenChange={setIsRegenerateConfirmationOpen}
        >
          <Modal className="w-full max-w-md">
            <Dialog aria-labelledby="regenerate-activation-title">
              <div className="p-6">
                <h2
                  id="regenerate-activation-title"
                  className="m-0 text-lg font-semibold text-primary"
                >
                  Regenerate activation link?
                </h2>
                <p className="mt-2 mb-0 text-sm text-tertiary">
                  The old shared activation link will stop working immediately.
                </p>
              </div>
              <div className="flex justify-between gap-3 border-t border-secondary px-6 py-4">
                <Button
                  color="tertiary"
                  size="sm"
                  onPress={() => setIsRegenerateConfirmationOpen(false)}
                  isDisabled={isRegenerating}
                >
                  Cancel
                </Button>
                <Button
                  color="primary-destructive"
                  size="sm"
                  onPress={handleRegenerateActivationLink}
                  isLoading={isRegenerating}
                >
                  Regenerate link
                </Button>
              </div>
            </Dialog>
          </Modal>
        </ModalOverlay>
      </section>
    </PageLayout>
  );
}

function ActivationAlert({
  user,
  publicUrl,
  onCopyActivationLink,
}: {
  user: ManagedUser;
  publicUrl?: string;
  onCopyActivationLink: () => void;
}) {
  if (user.active) {
    return null;
  }

  const activationUrl = getActivationUrl(user, publicUrl);
  if (!activationUrl) {
    return (
      <div className="renalo-alert renalo-alert--error" role="alert">
        <strong>Activation unavailable</strong>
        <p>
          This user cannot activate their account because no valid activation
          link exists.
        </p>
      </div>
    );
  }

  return (
    <div className="renalo-alert renalo-alert--warning" role="alert">
      <strong>Activation required</strong>
      <p>Share this link with the user so they can activate their account.</p>
      <div className="activation-link-row">
        <input readOnly value={activationUrl} aria-label="Activation link" />
        <Button color="tertiary" size="sm" onPress={onCopyActivationLink}>
          Copy link
        </Button>
      </div>
    </div>
  );
}

function getActivationUrl(
  user: ManagedUser | undefined,
  publicUrl: string | undefined,
) {
  if (!user?.activationToken || !publicUrl) {
    return undefined;
  }

  return `${publicUrl.replace(/\/$/, "")}/activate-account?token=${encodeURIComponent(user.activationToken.token)}`;
}

async function copyText(value: string) {
  if (navigator.clipboard) {
    try {
      await navigator.clipboard.writeText(value);
      return;
    } catch {
      // Fall back for browser contexts where clipboard permission is denied.
    }
  }

  const textArea = document.createElement("textarea");
  textArea.value = value;
  textArea.style.position = "fixed";
  textArea.style.opacity = "0";
  document.body.append(textArea);
  textArea.select();

  try {
    if (!document.execCommand("copy")) {
      throw new Error("Copy command failed");
    }
  } finally {
    textArea.remove();
  }
}
