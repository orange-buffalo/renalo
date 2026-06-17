import { type FormEvent, useState } from "react";
import { useNavigate } from "react-router";
import type { UserType } from "@/api/auth";
import { ApiError, apiRequest } from "@/api/client";
import { PageLayout } from "@/components/PageLayout";
import { Button } from "@/components/untitled/base/buttons/button";
import { Input } from "@/components/untitled/base/input/input";
import { Select } from "@/components/untitled/base/select/select";

const userTypeItems = [
  { id: "USER", label: "User" },
  { id: "ADMIN", label: "Admin" },
];

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
      await apiRequest("/api/users", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: trimmedUsername, type }),
      });
      navigate("/user-management");
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
    <PageLayout eyebrow="Administration" title="Create user">
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
          <Select
            label="Type"
            name="type"
            size="md"
            selectedKey={type}
            onSelectionChange={(key) => setType(key as UserType)}
            items={userTypeItems}
          >
            {(item) => <Select.Item id={item.id}>{item.label}</Select.Item>}
          </Select>

          <p className="create-user-password-note">
            A password will be generated automatically. New users are inactive
            until activation is implemented.
          </p>

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
              color="link-gray"
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
