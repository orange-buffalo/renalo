import type { ReactNode } from "react";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, Navigate, RouterProvider } from "react-router";
import { AppStateProvider, useAppState } from "./AppState.tsx";
import type { UserType } from "./api/auth.ts";
import { LoadingPage } from "./components/AnonymousPage.tsx";
import { Notifications } from "./components/untitled/application/notifications/notifications.tsx";
import { AccountAdjustmentsPage } from "./routes/AccountAdjustmentsPage.tsx";
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
  CreateFundsTransferPage,
  EditFundsTransferPage,
} from "./routes/FundsTransferFormPage.tsx";
import { FundsTransfersPage } from "./routes/FundsTransfersPage.tsx";
import {
  CreateIncomeCategoryPage,
  EditIncomeCategoryPage,
} from "./routes/IncomeCategoryFormPage.tsx";
import { CreateIncomePage, EditIncomePage } from "./routes/IncomeFormPage.tsx";
import { IncomesPage } from "./routes/IncomesPage.tsx";
import { LoginPage } from "./routes/LoginPage.tsx";
import {
  MergeExpenseCategoryPage,
  MergeIncomeCategoryPage,
} from "./routes/MergeCategoryPage.tsx";
import { MergeTrackingAccountPage } from "./routes/MergeTrackingAccountPage.tsx";
import { ProfilePage } from "./routes/ProfilePage.tsx";
import { SettingsPage } from "./routes/SettingsPage.tsx";
import { SignInLinkPage } from "./routes/SignInLinkPage.tsx";
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
    path: "/sign-in-link",
    element: <SignInLinkPage />,
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
    path: "/expenses/:transactionId",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <EditExpensePage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/incomes",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <IncomesPage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/incomes/create",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <CreateIncomePage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/incomes/:transactionId",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <EditIncomePage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/transfers",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <FundsTransfersPage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/transfers/create",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <CreateFundsTransferPage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/transfers/:transferId",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <EditFundsTransferPage />
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
    path: "/settings/accounts/:accountId/merge",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <MergeTrackingAccountPage />
      </ProtectedRoute>
    ),
  },
  {
    path: "/settings/accounts/:accountId/adjustments",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <AccountAdjustmentsPage />
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
    path: "/settings/expense-categories/:categoryId/merge",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <MergeExpenseCategoryPage />
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
    path: "/settings/income-categories/:categoryId/merge",
    element: (
      <ProtectedRoute allowedTypes={["USER"]}>
        <MergeIncomeCategoryPage />
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
