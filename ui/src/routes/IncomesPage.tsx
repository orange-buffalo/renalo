import { fetchIncomeCategories } from "@/api/incomeCategories";
import { incomeTransactionApi } from "@/api/transactions";
import {
  TransactionsPage,
  type TransactionsPageConfig,
} from "@/components/transactions/TransactionsPage";

const incomeTransactionsPageConfig: TransactionsPageConfig = {
  api: incomeTransactionApi,
  routeBasePath: "/incomes",
  title: "Incomes",
  description:
    "Review earnings entries and keep your budget history up to date.",
  addLabel: "Add income",
  loadingLabel: "Loading incomes",
  emptyTitle: "No incomes found",
  tableLabel: "Incomes",
  categoryColumnLabel: "Income category",
  fetchCategories: fetchIncomeCategories,
  itemLabel: "income",
  deleteTitle: (transaction) => `Delete ${transaction.category.name} income?`,
  deleteDescription:
    "This income will be removed from your budget history immediately.",
  deleteConfirmLabel: "Delete income",
  deleteError: "Income could not be deleted. Try again in a moment.",
  loadError: "Incomes could not be loaded. Try again in a moment.",
  plannedGroupLabel: "Planned incomes",
  rowTestIdPrefix: "income-row",
  deleteDialogDataTestId: "delete-income-overlay",
  groupingStorageKey: "renalo.incomes.tableGrouping",
};

export function IncomesPage() {
  return <TransactionsPage config={incomeTransactionsPageConfig} />;
}
