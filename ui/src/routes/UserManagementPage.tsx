import { Plus } from "@untitledui/icons";
import { useEffect, useState } from "react";
import { useLocation, useNavigate } from "react-router";
import type { UserType } from "@/api/auth";
import { apiRequest } from "@/api/client";
import { ConfirmationDialog } from "@/components/ConfirmationDialog";
import { PageLayout } from "@/components/PageLayout";
import { TableEmptyState } from "@/components/TableEmptyState";
import { TableLoadingState } from "@/components/TableLoadingState";
import {
  TableDeleteAction,
  TableEditAction,
  TableRowActions,
} from "@/components/TableRowActions";
import { showNotification } from "@/components/untitled/application/notifications/notifications";
import { PaginationCardDefault } from "@/components/untitled/application/pagination/pagination";
import {
  Table,
  TableCard,
} from "@/components/untitled/application/table/table";
import { Badge, BadgeWithDot } from "@/components/untitled/base/badges/badges";
import { Button } from "@/components/untitled/base/buttons/button";

const pageSize = 5;

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

type UsersPage = {
  users: ManagedUser[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export function UserManagementPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const [usersPage, setUsersPage] = useState<UsersPage>();
  const [page, setPage] = useState(0);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [confirmingUser, setConfirmingUser] = useState<ManagedUser>();
  const [deletingUserId, setDeletingUserId] = useState<number>();

  useEffect(() => {
    const state = location.state as {
      notification?: { title: string; description?: string };
    } | null;
    if (!state?.notification) {
      return;
    }

    showNotification(state.notification);
    navigate(location.pathname, { replace: true, state: null });
  }, [location.pathname, location.state, navigate]);

  useEffect(() => {
    let isActive = true;
    setIsLoading(true);
    setError(undefined);

    fetchUsers(page, pageSize)
      .then((nextUsersPage) => {
        if (!isActive) {
          return;
        }
        setUsersPage(nextUsersPage);
      })
      .catch(() => {
        if (!isActive) {
          return;
        }
        setError("Users could not be loaded. Try again in a moment.");
      })
      .finally(() => {
        if (isActive) {
          setIsLoading(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, [page]);

  async function handleDeleteConfirmed() {
    if (!confirmingUser) {
      return;
    }

    setDeletingUserId(confirmingUser.id);
    setError(undefined);

    try {
      const removedUsername = confirmingUser.username;
      await deleteUser(confirmingUser.id);
      setConfirmingUser(undefined);
      const nextPage =
        usersPage?.users.length === 1 && page > 0 ? page - 1 : page;
      if (nextPage !== page) {
        setPage(nextPage);
      } else {
        setIsLoading(true);
        setUsersPage(await fetchUsers(nextPage, pageSize));
      }
      showNotification({
        title: "User removed.",
        description: `${removedUsername} no longer has access to Renalo.`,
      });
    } catch {
      setError("User could not be removed. Try again in a moment.");
    } finally {
      setIsLoading(false);
      setDeletingUserId(undefined);
    }
  }

  const users = usersPage?.users ?? [];
  const displayTotalPages = Math.max(usersPage?.totalPages ?? 1, 1);
  const userCountLabel = usersPage
    ? `${usersPage.totalElements} user${usersPage.totalElements === 1 ? "" : "s"}`
    : undefined;

  return (
    <PageLayout
      title="User management"
      description="Manage Renalo users and keep administrative access organized."
      titleTrailing={
        userCountLabel ? (
          <Badge color="gray" size="sm" type="modern">
            {userCountLabel}
          </Badge>
        ) : undefined
      }
      actions={
        <Button
          color="tertiary"
          size="sm"
          iconLeading={Plus}
          onPress={() => navigate("/user-management/create")}
        >
          Create user
        </Button>
      }
    >
      <section className="user-management-panel">
        <TableCard.Root size="sm">
          {error && (
            <p
              className="user-management-message user-management-error"
              role="alert"
            >
              {error}
            </p>
          )}

          {isLoading && !usersPage ? (
            <TableLoadingState label="Loading users" />
          ) : usersPage && users.length === 0 ? (
            <TableEmptyState title="No users found" />
          ) : (
            <Table aria-label="Users" size="sm">
              <Table.Header>
                <Table.Head id="username" label="Username" isRowHeader />
                <Table.Head id="type" label="Type" />
                <Table.Head id="active" label="Active" />
                <Table.Head
                  id="actions"
                  label="Actions"
                  mobileRole="actions"
                  className="[&>div]:justify-end"
                />
              </Table.Header>
              <Table.Body>
                {users.map((user) => (
                  <Table.Row
                    id={user.id}
                    key={user.id}
                    data-testid={`user-row-${user.id}`}
                  >
                    <Table.Cell>{user.username}</Table.Cell>
                    <Table.Cell>{userTypeLabels[user.type]}</Table.Cell>
                    <Table.Cell mobileLabel="Active" mobileRole="detail">
                      <BadgeWithDot
                        color={user.active ? "success" : "gray"}
                        size="sm"
                      >
                        {user.active ? "Active" : "Inactive"}
                      </BadgeWithDot>
                    </Table.Cell>
                    <Table.Cell mobileRole="actions">
                      <TableRowActions>
                        <TableEditAction
                          label={`Edit ${user.username}`}
                          onPress={() =>
                            navigate(`/user-management/${user.id}`)
                          }
                        />
                        {!user.currentUser && (
                          <TableDeleteAction
                            label={`Remove ${user.username}`}
                            onPress={() => setConfirmingUser(user)}
                            isDisabled={deletingUserId === user.id}
                            actionIcon="trash"
                          />
                        )}
                      </TableRowActions>
                    </Table.Cell>
                  </Table.Row>
                ))}
              </Table.Body>
            </Table>
          )}

          {usersPage && (
            <PaginationCardDefault
              page={page + 1}
              total={displayTotalPages}
              onPageChange={(nextPage) => setPage(nextPage - 1)}
            />
          )}
        </TableCard.Root>

        {confirmingUser && (
          <ConfirmationDialog
            dataTestId="remove-user-overlay"
            isOpen={Boolean(confirmingUser)}
            title={`Remove ${confirmingUser.username}?`}
            description="This user will lose access to Renalo immediately."
            confirmLabel="Remove user"
            isConfirming={deletingUserId === confirmingUser.id}
            onCancel={() => setConfirmingUser(undefined)}
            onConfirm={handleDeleteConfirmed}
          />
        )}
      </section>
    </PageLayout>
  );
}

async function fetchUsers(page: number, size: number) {
  const query = new URLSearchParams({
    page: String(page),
    size: String(size),
  });

  return apiRequest<UsersPage>(`/api/users?${query}`);
}

async function deleteUser(id: number) {
  await apiRequest<void>(`/api/users/${id}`, { method: "DELETE" });
}
