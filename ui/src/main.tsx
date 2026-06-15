import type { ReactNode } from "react";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, Navigate, RouterProvider } from "react-router";
import { AppStateProvider, useAppState } from "./AppState.tsx";
import type { UserType } from "./api/auth.ts";
import { LoginPage } from "./routes/LoginPage.tsx";
import { TrackingPage } from "./routes/TrackingPage.tsx";
import { UserManagementPage } from "./routes/UserManagementPage.tsx";

const router = createBrowserRouter([
  {
    path: "/",
    element: <LoginPage />,
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
    path: "/user-management",
    element: (
      <ProtectedRoute allowedTypes={["ADMIN"]}>
        <UserManagementPage />
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

function LoadingPage() {
  return (
    <main className="loading-shell" aria-label="Loading Renalo">
      <section className="loading-card">
        <div className="loading-brand">
          <span className="loading-logo" aria-hidden="true">
            R
          </span>
          <span>Renalo</span>
        </div>
        <p>Loading your workspace...</p>
      </section>
    </main>
  );
}

function defaultRouteFor(userType: UserType) {
  return userType === "ADMIN" ? "/user-management" : "/tracking";
}
