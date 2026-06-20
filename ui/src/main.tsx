import type { ReactNode } from "react";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, Navigate, RouterProvider } from "react-router";
import { AppStateProvider, useAppState } from "./AppState.tsx";
import type { UserType } from "./api/auth.ts";
import { LoadingPage } from "./components/AnonymousPage.tsx";
import { Notifications } from "./components/untitled/application/notifications/notifications.tsx";
import { ActivateAccountPage } from "./routes/ActivateAccountPage.tsx";
import { CreateUserPage } from "./routes/CreateUserPage.tsx";
import { EditUserPage } from "./routes/EditUserPage.tsx";
import {
  CreateExpenseCategoryPage,
  EditExpenseCategoryPage,
} from "./routes/ExpenseCategoryFormPage.tsx";
import {
  CreateExpensePage,
  EditExpensePage,
} from "./routes/ExpenseFormPage.tsx";
import { ExpensesPage } from "./routes/ExpensesPage.tsx";
import {
  CreateIncomeCategoryPage,
  EditIncomeCategoryPage,
} from "./routes/IncomeCategoryFormPage.tsx";
import { LoginPage } from "./routes/LoginPage.tsx";
import { ProfilePage } from "./routes/ProfilePage.tsx";
import { SettingsPage } from "./routes/SettingsPage.tsx";
import {
  CreateTrackingAccountPage,
  EditTrackingAccountPage,
} from "./routes/TrackingAccountFormPage.tsx";
import { TrackingPage } from "./routes/TrackingPage.tsx";
import { UserManagementPage } from "./routes/UserManagementPage.tsx";

const router = createBrowserRouter([
  {
    path: "/",
    element: <LoginPage />,
  },
  {
    path: "/activate-account",
    element: <ActivateAccountPage />,
  },
  {
    path: "/tracking",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <TrackingPage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/expenses",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <ExpensesPage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/expenses/create",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <CreateExpensePage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/expenses/:expenseId",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <EditExpensePage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/settings",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <SettingsPage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/profile",
    element: (
      <ProtectedRoute allowedTypes={["USER", "ADMIN"]}>
        <ProfilePage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/settings/accounts/create",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <CreateTrackingAccountPage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/settings/accounts/:accountId",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <EditTrackingAccountPage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/settings/expense-categories/create",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <CreateExpenseCategoryPage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/settings/expense-categories/:categoryId",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <EditExpenseCategoryPage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/settings/income-categories/create",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <CreateIncomeCategoryPage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/settings/income-categories/:categoryId",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <EditIncomeCategoryPage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/user-management",
    element: (
      <ProtectedRoute allowedTypes={["ADMIN"]}>
        <UserManagementPage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/user-management/create",
    element: (
      <ProtectedRoute allowedTypes={["ADMIN"]}>
        <CreateUserPage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/user-management/:userId",
    element: (
      <ProtectedRoute allowedTypes={["ADMIN"]}>
        <EditUserPage />
      </ProtectedRoute>
    ),
  },
  {
    path: "*",
    element: <LoginPage />,
  },
]);

const root = document.getElementById("root");

if (!root) {
  throw new Error("Root element was not found");
}

createRoot(root).render(
  <StrictMode>
    <AppStateProvider>
      <RouterProvider router={router} />
      <Notifications />
    </AppStateProvider>
  </StrictMode>,
);

function ProtectedRoute({
  allowedTypes,
  children,
}: {
  allowedTypes: UserType[];
  children: ReactNode;
}) {
  const { authStatus, profile } = useAppState();

  if (authStatus === "checking") {
    return <LoadingPage />;
  }

  if (!profile) {
    return <Navigate to="/" replace />;
  }

  if (!allowedTypes.includes(profile.type)) {
    return <Navigate to={defaultRouteFor(profile.type)} replace />;
  }

  return children;
}

function defaultRouteFor(userType: UserType) {
  return userType === "ADMIN" ? "/user-management" : "/tracking";
}
