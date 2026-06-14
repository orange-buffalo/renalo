import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, RouterProvider, useLocation } from "react-router";
import type { Profile } from "./api/auth.ts";
import { LoginPage } from "./routes/LoginPage.tsx";
import { TrackingPage } from "./routes/TrackingPage.tsx";
import { UserManagementPage } from "./routes/UserManagementPage.tsx";

type RouteState = {
  profile?: Profile;
};

function TrackingRoute() {
  const location = useLocation();
  return (
    <TrackingPage profile={(location.state as RouteState | null)?.profile} />
  );
}

function UserManagementRoute() {
  const location = useLocation();
  return (
    <UserManagementPage
      profile={(location.state as RouteState | null)?.profile}
    />
  );
}

const router = createBrowserRouter([
  {
    path: "/",
    element: <LoginPage />,
  },
  {
    path: "/tracking",
    element: <TrackingRoute />,
  },
  {
    path: "/user-management",
    element: <UserManagementRoute />,
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
    <RouterProvider router={router} />
  </StrictMode>,
);
