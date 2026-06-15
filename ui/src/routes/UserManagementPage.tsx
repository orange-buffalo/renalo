import { useEffect, useState } from "react";
import {
  deleteUser,
  fetchUsers,
  type ManagedUser,
  type UsersPage,
} from "@/api/auth";
import { PageLayout } from "@/components/PageLayout";
import { Button } from "@/components/untitled/base/buttons/button";

const pageSize = 5;

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
      <section className="standard-page-panel user-management-panel">
        <div className="user-management-panel-header">
          <div>
            <h2>Workspace access</h2>
            <p>Manage Renalo users and keep administrative access organized.</p>
          </div>
          {usersPage && (
            <p className="user-management-total">
              {usersPage.totalElements} user
              {usersPage.totalElements === 1 ? "" : "s"}
            </p>
          )}
        </div>

        {error && (
          <p className="user-management-error" role="alert">
            {error}
          </p>
        )}

        {isLoading && !usersPage ? (
          <p className="user-management-loading">Loading users...</p>
        ) : (
          <div className="user-management-table-scroll">
            <table className="user-management-table" aria-label="Users">
              <thead>
                <tr>
                  <th scope="col">Username</th>
                  <th scope="col">Type</th>
                  <th scope="col">Actions</th>
                </tr>
              </thead>
              <tbody>
                {users.map((user) => (
                  <tr key={user.id} data-testid={`user-row-${user.id}`}>
                    <td>{user.username}</td>
                    <td>{user.type}</td>
                    <td>
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
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {usersPage && users.length === 0 && !isLoading && (
          <p className="user-management-empty">No users found.</p>
        )}

        {usersPage && (
          <div className="user-management-pagination">
            <Button
              color="secondary"
              size="sm"
              onPress={() => setPage((current) => Math.max(current - 1, 0))}
              isDisabled={page === 0 || isLoading}
            >
              Previous
            </Button>
            <span>
              Page {page + 1} of {displayTotalPages}
            </span>
            <Button
              color="secondary"
              size="sm"
              onPress={() => setPage((current) => current + 1)}
              isDisabled={page + 1 >= displayTotalPages || isLoading}
            >
              Next
            </Button>
          </div>
        )}

        {confirmingUser && (
          <div
            className="user-management-confirmation"
            role="dialog"
            aria-labelledby="remove-user-title"
          >
            <div>
              <h3 id="remove-user-title">Remove {confirmingUser.username}?</h3>
              <p>This user will lose access to Renalo immediately.</p>
            </div>
            <div className="user-management-confirmation-actions">
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
          </div>
        )}
      </section>
    </PageLayout>
  );
}
