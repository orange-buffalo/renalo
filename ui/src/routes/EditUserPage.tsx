import { type FormEvent, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router";
import type { UserType } from "@/api/auth";
import { ApiError, apiRequest } from "@/api/client";
import { PageLayout } from "@/components/PageLayout";
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
};

export function EditUserPage() {
  const navigate = useNavigate();
  const { userId } = useParams();
  const [user, setUser] = useState<ManagedUser>();
  const [username, setUsername] = useState("");
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState<string>();
  const [usernameError, setUsernameError] = useState<string>();

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
    >
      <section className="standard-page-panel edit-user-panel">
        {isLoading ? (
          <p className="user-management-message">Loading user...</p>
        ) : user ? (
          <form className="edit-user-form" onSubmit={handleSubmit}>
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
      </section>
    </PageLayout>
  );
}
