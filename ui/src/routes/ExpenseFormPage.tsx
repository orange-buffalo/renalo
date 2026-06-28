import { fetchExpenseCategories } from "@/api/expenseCategories";
import { expenseTransactionApi } from "@/api/transactions";
import {
  type TransactionFormConfig,
  TransactionFormPage,
} from "@/components/transactions/TransactionFormPage";

const expenseTransactionFormConfig: TransactionFormConfig = {
  api: expenseTransactionApi,
  routeBasePath: "/expenses",
  storageKey: "renalo.expenses.lastRecurrenceConfiguration",
  categoryLabel: "Category",
  categoryPlaceholder: "Choose category",
  categoryError: "Choose a category.",
  optionsLoadError:
    "Expense form options could not be loaded. Try again in a moment.",
  loadError: "Expense could not be loaded. Try again in a moment.",
  saveError: "Expense could not be saved. Try again in a moment.",
  title: (isEditing) => (isEditing ? "Edit expense" : "Add expense"),
  description: "Record spending against an account and expense category.",
  recurringCheckboxLabel: "Recurring expense",
  recurringCheckboxHint: "Generate matching expense rows from this schedule.",
  createButtonLabel: "Create expense",
  saveButtonLabel: "Save expense",
  fetchCategories: fetchExpenseCategories,
};

export function CreateExpensePage() {
  return (
    <TransactionFormPage config={expenseTransactionFormConfig} mode="create" />
  );
}

export function EditExpensePage() {
  return (
    <TransactionFormPage config={expenseTransactionFormConfig} mode="edit" />
  );
}
