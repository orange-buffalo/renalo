import { fetchIncomeCategories } from "@/api/incomeCategories";
import { incomeTransactionApi } from "@/api/transactions";
import {
  type TransactionFormConfig,
  TransactionFormPage,
} from "@/components/transactions/TransactionFormPage";

const incomeTransactionFormConfig: TransactionFormConfig = {
  api: incomeTransactionApi,
  routeBasePath: "/incomes",
  storageKey: "renalo.incomes.lastRecurrenceConfiguration",
  categoryLabel: "Income category",
  categoryPlaceholder: "Choose income category",
  categoryError: "Choose an income category.",
  optionsLoadError:
    "Income form options could not be loaded. Try again in a moment.",
  loadError: "Income could not be loaded. Try again in a moment.",
  saveError: "Income could not be saved. Try again in a moment.",
  title: (isEditing) => (isEditing ? "Edit income" : "Add income"),
  description: "Record earnings against an account and income category.",
  recurringCheckboxLabel: "Recurring income",
  recurringCheckboxHint: "Generate matching income rows from this schedule.",
  createButtonLabel: "Create income",
  saveButtonLabel: "Save income",
  fetchCategories: fetchIncomeCategories,
};

export function CreateIncomePage() {
  return (
    <TransactionFormPage config={incomeTransactionFormConfig} mode="create" />
  );
}

export function EditIncomePage() {
  return (
    <TransactionFormPage config={incomeTransactionFormConfig} mode="edit" />
  );
}
