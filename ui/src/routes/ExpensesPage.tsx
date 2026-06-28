import { Plus } from "@untitledui/icons";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import {
  deleteExpense,
  type Expense,
  fetchExpenses,
  type RecurringExpenseDeleteScope,
} from "@/api/expenses";
import { ConfirmationDialog } from "@/components/ConfirmationDialog";
import { PageLayout } from "@/components/PageLayout";
import { TableEmptyState } from "@/components/TableEmptyState";
import { TableLoadingState } from "@/components/TableLoadingState";
import {
  TableDeleteAction,
  TableEditAction,
  TableMobileDetailsAction,
  TableRowActions,
} from "@/components/TableRowActions";
import {
  Table,
  TableCard,
} from "@/components/untitled/application/table/table";
import { Button } from "@/components/untitled/base/buttons/button";
import {
  RadioButton,
  RadioGroup,
} from "@/components/untitled/base/radio-buttons/radio-buttons";
import { formatMoney } from "@/utils/money";

export function ExpensesPage() {
  const navigate = useNavigate();
  const [expenses, setExpenses] = useState<Expense[]>();
  const [error, setError] = useState<string>();
  const [confirmingExpense, setConfirmingExpense] = useState<Expense>();
  const [recurringDeleteScope, setRecurringDeleteScope] =
    useState<RecurringExpenseDeleteScope>("THIS_OCCURRENCE_ONLY");
  const [deletingExpenseId, setDeletingExpenseId] = useState<number>();

  useEffect(() => {
    let isActive = true;
    fetchExpenses()
      .then((loadedExpenses) => {
        if (isActive) {
          setExpenses(loadedExpenses);
        }
      })
      .catch(() => {
        if (isActive) {
          setError("Expenses could not be loaded. Try again in a moment.");
        }
      });

    return () => {
      isActive = false;
    };
  }, []);

  async function handleDeleteConfirmed() {
    if (!confirmingExpense) {
      return;
    }

    setDeletingExpenseId(confirmingExpense.id);
    setError(undefined);
    try {
      await deleteExpense(
        confirmingExpense.id,
        confirmingExpense.recurrence ? recurringDeleteScope : undefined,
      );
      if (confirmingExpense.recurrence) {
        setExpenses(await fetchExpenses());
      } else {
        setExpenses((currentExpenses) =>
          currentExpenses?.filter(
            (currentExpense) => currentExpense.id !== confirmingExpense.id,
          ),
        );
      }
      setConfirmingExpense(undefined);
    } catch {
      setError("Expense could not be deleted. Try again in a moment.");
    } finally {
      setDeletingExpenseId(undefined);
    }
  }

  return (
    <PageLayout
      title="Expenses"
      description="Review spending entries and keep your budget history up to date."
      actions={
        <Button
          color="tertiary"
          size="sm"
          iconLeading={Plus}
          onPress={() => navigate("/expenses/create")}
        >
          Add expense
        </Button>
      }
    >
      <section className="standard-page-panel user-management-panel">
        <TableCard.Root size="sm">
          {error && (
            <p
              className="user-management-message user-management-error"
              role="alert"
            >
              {error}
            </p>
          )}
          {!expenses ? (
            <TableLoadingState label="Loading expenses" />
          ) : expenses.length === 0 ? (
            <TableEmptyState title="No expenses found" />
          ) : (
            <Table aria-label="Expenses" size="sm">
              <Table.Header>
                <Table.Head id="category" label="Category" isRowHeader />
                <Table.Head id="amount" label="Amount" />
                <Table.Head id="date" label="Date" />
                <Table.Head id="account" label="Account" />
                <Table.Head id="notes" label="Notes" />
                <Table.Head
                  id="actions"
                  label="Actions"
                  mobileRole="actions"
                  className="[&>div]:justify-end"
                />
              </Table.Header>
              <Table.Body>
                {expenses.map((expense) => (
                  <Table.Row
                    id={expense.id}
                    key={expense.id}
                    data-testid={`expense-row-${expense.id}`}
                  >
                    <Table.Cell>{expense.category.name}</Table.Cell>
                    <Table.Cell>
                      {formatMoney(
                        expense.amountMinor,
                        expense.trackingAccount.currency,
                      )}
                    </Table.Cell>
                    <Table.Cell mobileLabel="Date" mobileRole="detail">
                      <span>{formatExpenseDate(expense.date)}</span>
                      {expense.recurrence && isActiveRecurrence(expense) && (
                        <span className="expense-recurrence-description">
                          {expense.recurrence.description}
                        </span>
                      )}
                    </Table.Cell>
                    <Table.Cell mobileLabel="Account">
                      {expense.trackingAccount.name}
                    </Table.Cell>
                    <Table.Cell mobileLabel="Notes">
                      {expense.notes || "-"}
                    </Table.Cell>
                    <Table.Cell mobileRole="actions">
                      <TableRowActions>
                        <TableMobileDetailsAction
                          label={`Show ${expense.category.name} details`}
                        />
                        <TableEditAction
                          label={`Edit ${expense.category.name} expense`}
                          onPress={() => navigate(`/expenses/${expense.id}`)}
                        />
                        <TableDeleteAction
                          label={`Delete ${expense.category.name} expense`}
                          isLoading={deletingExpenseId === expense.id}
                          onPress={() => {
                            setRecurringDeleteScope("THIS_OCCURRENCE_ONLY");
                            setConfirmingExpense(expense);
                          }}
                        />
                      </TableRowActions>
                    </Table.Cell>
                  </Table.Row>
                ))}
              </Table.Body>
            </Table>
          )}
        </TableCard.Root>

        {confirmingExpense && (
          <ConfirmationDialog
            dataTestId="delete-expense-overlay"
            isOpen={Boolean(confirmingExpense)}
            title={`Delete ${confirmingExpense.category.name} expense?`}
            description="This expense will be removed from your budget history immediately."
            confirmLabel="Delete expense"
            isConfirming={deletingExpenseId === confirmingExpense.id}
            onCancel={() => setConfirmingExpense(undefined)}
            onConfirm={handleDeleteConfirmed}
          >
            {confirmingExpense.recurrence && (
              <>
                <p className="expense-recurrence-delete-context">
                  {formatRecurringDeleteContext(confirmingExpense)}
                </p>
                <RadioGroup
                  aria-label="Delete scope"
                  value={recurringDeleteScope}
                  onChange={(scope) =>
                    setRecurringDeleteScope(
                      scope as RecurringExpenseDeleteScope,
                    )
                  }
                >
                  <RadioButton
                    value="THIS_OCCURRENCE_ONLY"
                    label="This occurrence only"
                    hint="Delete only this generated expense."
                  />
                  <RadioButton
                    value="THIS_AND_ALL_FOLLOWING_OCCURRENCES"
                    label="This and all following occurrences"
                    hint="Delete this expense and every later expense in the series."
                  />
                  <RadioButton
                    value="ALL_OCCURRENCES"
                    label="All occurrences"
                    hint="Delete the entire recurring expense series."
                  />
                </RadioGroup>
              </>
            )}
          </ConfirmationDialog>
        )}
      </section>
    </PageLayout>
  );
}

function formatExpenseDate(isoDate: string) {
  const [year, month, day] = isoDate.split("-").map(Number);
  const date = new Date(year, month - 1, day);
  const today = startOfLocalDay(new Date());
  const expenseDay = startOfLocalDay(date);
  const dayDifference = Math.round(
    (today.getTime() - expenseDay.getTime()) / 86_400_000,
  );
  if (dayDifference === 0) {
    return "Today";
  }
  if (dayDifference === 1) {
    return "Yesterday";
  }

  return new Intl.DateTimeFormat(undefined, {
    day: "numeric",
    month: "short",
  }).format(date);
}

function startOfLocalDay(date: Date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}

function isActiveRecurrence(expense: Expense) {
  const endDate = expense.recurrence?.endDate;
  if (!endDate) {
    return true;
  }

  return startOfLocalDay(parseIsoDate(endDate)) > startOfLocalDay(new Date());
}

function parseIsoDate(isoDate: string) {
  const [year, month, day] = isoDate.split("-").map(Number);
  return new Date(year, month - 1, day);
}

function formatRecurringDeleteContext(expense: Expense) {
  const recurrence = expense.recurrence;
  if (!recurrence) {
    return "";
  }

  const startDate = formatShortDate(recurrence.startDate);
  if (!recurrence.endDate) {
    return `This expense is part of the repeated series starting ${startDate} with no end date.`;
  }

  return `This expense is part of the repeated series starting ${startDate} and ending ${formatShortDate(recurrence.endDate)}.`;
}

function formatShortDate(isoDate: string) {
  return new Intl.DateTimeFormat("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
  }).format(parseIsoDate(isoDate));
}
