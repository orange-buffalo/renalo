import { type FormEvent, useState } from "react";
import { useNavigate } from "react-router";
import type { UserType } from "@/api/auth";
import { ApiError, apiRequest } from "@/api/client";
import { PageLayout } from "@/components/PageLayout";
import { SearchableDropdown } from "@/components/SearchableDropdown";
import { Button } from "@/components/untitled/base/buttons/button";
import { Input } from "@/components/untitled/base/input/input";

const userTypeItems = [
  { id: "USER", label: "User" },
  { id: "ADMIN", label: "Admin" },
];

type CreatedUser = {
  id: number;
};

export function CreateUserPage() {
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [type, setType] = useState<UserType>("USER");
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState<string>();
  const [usernameError, setUsernameError] = useState<string>();

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmedUsername = username.trim();

    if (!trimmedUsername) {
      setUsernameError("Enter a username.");
      return;
    }

    setIsSaving(true);
    setError(undefined);
    setUsernameError(undefined);

    try {
      const createdUser = await apiRequest<CreatedUser>("/api/users", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: trimmedUsername, type }),
      });
      navigate(`/user-management/${createdUser.id}`, {
        state: {
          notification: {
            title: "User created.",
            description: "Share the activation link to finish setup.",
          },
        },
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

      setError("User could not be created. Check the username and try again.");
    } finally {
      setIsSaving(false);
    }
  }

  return (
    <PageLayout
      title="Create user"
      description="A password will be generated automatically. New users stay inactive until they use their activation link."
    >
      <section className="standard-page-panel create-user-panel">
        <form className="create-user-form" onSubmit={handleSubmit}>
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
          <SearchableDropdown
            label="Type"
            placeholder="Choose user type"
            selectedKey={type}
            isRequired
            onSelectionChange={(key) => setType(key as UserType)}
            items={userTypeItems}
          />

          {error && (
            <p
              className="create-user-form-error user-management-error"
              role="alert"
            >
              {error}
            </p>
          )}

          <div className="create-user-actions">
            <Button
              color="tertiary"
              size="sm"
              onPress={() => navigate("/user-management")}
              isDisabled={isSaving}
            >
              Cancel
            </Button>
            <Button
              color="primary"
              size="sm"
              type="submit"
              isLoading={isSaving}
            >
              Create user
            </Button>
          </div>
        </form>
      </section>
    </PageLayout>
  );
}
