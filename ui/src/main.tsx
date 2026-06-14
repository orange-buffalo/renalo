import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { createBrowserRouter, RouterProvider } from "react-router";
import { AppStateProvider } from "./AppState.tsx";
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
    element: <TrackingPage />,
  },
  {
    path: "/user-management",
    element: <UserManagementPage />,
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
