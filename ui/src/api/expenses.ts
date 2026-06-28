import {
  createTransaction,
  deleteTransaction,
  expenseTransactionApi,
  fetchTransaction,
  fetchTransactions,
  type RecurringTransactionDeleteScope,
  type SaveTransaction,
  type Transaction,
  updateTransaction,
} from "@/api/transactions";

export type Expense = Transaction;
export type SaveExpense = SaveTransaction;
export type RecurringExpenseDeleteScope = RecurringTransactionDeleteScope;

export function fetchExpenses() {
  return fetchTransactions(expenseTransactionApi);
}

export function fetchExpense(expenseId: number) {
  return fetchTransaction(expenseTransactionApi, expenseId);
}

export function createExpense(expense: SaveExpense) {
  return createTransaction(expenseTransactionApi, expense);
}

export function updateExpense(expenseId: number, expense: SaveExpense) {
  return updateTransaction(expenseTransactionApi, expenseId, expense);
}

export function deleteExpense(
  expenseId: number,
  recurringDeleteScope?: RecurringExpenseDeleteScope,
) {
  return deleteTransaction(
    expenseTransactionApi,
    expenseId,
    recurringDeleteScope,
  );
}
