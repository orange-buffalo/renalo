import { fetchExpenseCategories } from "@/api/expenseCategories";
import { expenseTransactionApi } from "@/api/transactions";
import {
  TransactionsPage,
  type TransactionsPageConfig,
} from "@/components/transactions/TransactionsPage";

const expenseTransactionsPageConfig: TransactionsPageConfig = {
  api: expenseTransactionApi,
  routeBasePath: "/expenses",
  title: "Expenses",
  description:
    "Review spending entries and keep your budget history up to date.",
  addLabel: "Add expense",
  loadingLabel: "Loading expenses",
  emptyTitle: "No expenses found",
  tableLabel: "Expenses",
  categoryColumnLabel: "Category",
  fetchCategories: fetchExpenseCategories,
  itemLabel: "expense",
  deleteTitle: (transaction) => `Delete ${transaction.category.name} expense?`,
  deleteDescription:
    "This expense will be removed from your budget history immediately.",
  deleteConfirmLabel: "Delete expense",
  deleteError: "Expense could not be deleted. Try again in a moment.",
  loadError: "Expenses could not be loaded. Try again in a moment.",
  plannedGroupLabel: "Planned expenses",
  rowTestIdPrefix: "expense-row",
  deleteDialogDataTestId: "delete-expense-overlay",
  groupingStorageKey: "renalo.expenses.tableGrouping",
};

export function ExpensesPage() {
  return <TransactionsPage config={expenseTransactionsPageConfig} />;
}
