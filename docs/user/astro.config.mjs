import { defineConfig } from "astro/config";
import starlight from "@astrojs/starlight";

export default defineConfig({
  site: process.env.RENALO_DOCS_SITE_URL || "https://renalo-docs.orange-buffalo.io",
  output: "static",
  integrations: [
    starlight({
      title: "Renalo",
      description: "Install, operate, and use your private Renalo budgeting workspace.",
      logo: {
        src: "./src/assets/logo.svg",
        alt: "Renalo",
        replacesTitle: false,
      },
      favicon: "/favicon.svg",
      customCss: ["./src/styles/renalo.css"],
      social: [
        {
          icon: "github",
          label: "Renalo on GitHub",
          href: "https://github.com/orange-buffalo/renalo",
        },
      ],
      components: {
        ThemeProvider: "./src/components/LightThemeProvider.astro",
        ThemeSelect: "./src/components/Empty.astro",
      },
      sidebar: [
        { label: "Welcome", slug: "index" },
        {
          label: "Getting started",
          items: [
            { label: "Install with Docker", slug: "getting-started/installation" },
            { label: "First sign-in", slug: "getting-started/first-run" },
            { label: "Administrator recovery", slug: "getting-started/admin-recovery" },
          ],
        },
        {
          label: "Feature walkthrough",
          items: [
            { label: "Feature map", slug: "features" },
            { label: "Dashboard", slug: "features/dashboard" },
            { label: "Expenses", slug: "features/expenses" },
            { label: "Incomes", slug: "features/incomes" },
            { label: "Recurring entries", slug: "features/recurring-entries" },
            { label: "Transfers", slug: "features/transfers" },
            { label: "Accounts", slug: "features/accounts" },
            { label: "Categories", slug: "features/categories" },
            { label: "Balance adjustments", slug: "features/adjustments" },
            { label: "Toshl import", slug: "features/toshl-import" },
            { label: "Profile & sign-in security", slug: "features/profile-security" },
            { label: "User administration", slug: "features/user-administration" },
          ],
        },
      ],
      editLink: {
        baseUrl: "https://github.com/orange-buffalo/renalo/edit/main/docs/user/",
      },
    }),
  ],
});
