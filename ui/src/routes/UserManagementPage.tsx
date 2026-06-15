import { useEffect, useState } from "react";
import type { UserType } from "@/api/auth";
import { apiRequest } from "@/api/client";
import { PageLayout } from "@/components/PageLayout";
import {
  Dialog,
  Modal,
  ModalOverlay,
} from "@/components/untitled/application/modals/modal";
import { PaginationCardDefault } from "@/components/untitled/application/pagination/pagination";
import {
  Table,
  TableCard,
} from "@/components/untitled/application/table/table";
import { Button } from "@/components/untitled/base/buttons/button";

const pageSize = 5;

type ManagedUser = {
  id: number;
  username: string;
  type: UserType;
  currentUser: boolean;
};

type UsersPage = {
  users: ManagedUser[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export function UserManagementPage() {
  const [usersPage, setUsersPage] = useState<UsersPage>();
  const [page, setPage] = useState(0);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string>();
  const [confirmingUser, setConfirmingUser] = useState<ManagedUser>();
  const [deletingUserId, setDeletingUserId] = useState<number>();

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
    } catch {
      setError("User could not be removed. Try again in a moment.");
    } finally {
      setIsLoading(false);
      setDeletingUserId(undefined);
    }
  }

  const users = usersPage?.users ?? [];
  const displayTotalPages = Math.max(usersPage?.totalPages ?? 1, 1);

  return (
    <PageLayout eyebrow="Administration" title="User management">
      <section className="user-management-panel">
        <TableCard.Root size="sm">
          <TableCard.Header
            title="Workspace access"
            description="Manage Renalo users and keep administrative access organized."
            badge={
              usersPage
                ? `${usersPage.totalElements} user${usersPage.totalElements === 1 ? "" : "s"}`
                : undefined
            }
          />

          {error && (
            <p
              className="user-management-message user-management-error"
              role="alert"
            >
              {error}
            </p>
          )}

          {isLoading && !usersPage ? (
            <p className="user-management-message">Loading users...</p>
          ) : (
            <Table aria-label="Users" size="sm">
              <Table.Header>
                <Table.Head id="username" label="Username" isRowHeader />
                <Table.Head id="type" label="Type" />
                <Table.Head id="actions" label="Actions" />
              </Table.Header>
              <Table.Body>
                {users.map((user) => (
                  <Table.Row
                    id={user.id}
                    key={user.id}
                    data-testid={`user-row-${user.id}`}
                  >
                    <Table.Cell>{user.username}</Table.Cell>
                    <Table.Cell>{user.type}</Table.Cell>
                    <Table.Cell>
                      {user.currentUser ? (
                        <span className="user-management-current-user">
                          Current user
                        </span>
                      ) : (
                        <Button
                          color="link-destructive"
                          size="sm"
                          onPress={() => setConfirmingUser(user)}
                          isDisabled={deletingUserId === user.id}
                        >
                          Remove
                        </Button>
                      )}
                    </Table.Cell>
                  </Table.Row>
                ))}
              </Table.Body>
            </Table>
          )}

          {usersPage && users.length === 0 && !isLoading && (
            <p className="user-management-message">No users found.</p>
          )}

          {usersPage && (
            <PaginationCardDefault
              page={page + 1}
              total={displayTotalPages}
              onPageChange={(nextPage) => setPage(nextPage - 1)}
            />
          )}
        </TableCard.Root>

        <ModalOverlay
          isOpen={Boolean(confirmingUser)}
          isDismissable
          onOpenChange={(isOpen) => {
            if (!isOpen) {
              setConfirmingUser(undefined);
            }
          }}
        >
          {confirmingUser && (
            <Modal className="w-full max-w-md">
              <Dialog aria-labelledby="remove-user-title">
                <div className="p-6">
                  <h2
                    id="remove-user-title"
                    className="m-0 text-lg font-semibold text-primary"
                  >
                    Remove {confirmingUser.username}?
                  </h2>
                  <p className="mt-2 mb-0 text-sm text-tertiary">
                    This user will lose access to Renalo immediately.
                  </p>
                </div>
                <div className="flex justify-end gap-3 border-t border-secondary px-6 py-4">
                  <Button
                    color="secondary"
                    size="sm"
                    onPress={() => setConfirmingUser(undefined)}
                    isDisabled={deletingUserId === confirmingUser.id}
                  >
                    Cancel
                  </Button>
                  <Button
                    color="primary-destructive"
                    size="sm"
                    onPress={handleDeleteConfirmed}
                    isLoading={deletingUserId === confirmingUser.id}
                  >
                    Remove user
                  </Button>
                </div>
              </Dialog>
            </Modal>
          )}
        </ModalOverlay>
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
