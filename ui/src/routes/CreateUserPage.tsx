import { type FormEvent, useState } from "react";
import { useNavigate } from "react-router";
import type { UserType } from "@/api/auth";
import { apiRequest } from "@/api/client";
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

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setIsSaving(true);
    setError(undefined);

    try {
      await apiRequest("/api/users", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, type }),
      });
      navigate("/user-management");
    } catch {
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
            onChange={setUsername}
            isRequired
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
              className="user-management-message user-management-error"
              role="alert"
            >
              {error}
            </p>
          )}

          <div className="create-user-actions">
            <Button
              color="secondary"
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
