import { Edit02, Plus, Trash01 } from "@untitledui/icons";
import type { ComponentProps } from "react";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import { deleteExpense, type Expense, fetchExpenses } from "@/api/expenses";
import { PageLayout } from "@/components/PageLayout";
import { TableEmptyState } from "@/components/TableEmptyState";
import {
  Table,
  TableCard,
} from "@/components/untitled/application/table/table";
import { Button } from "@/components/untitled/base/buttons/button";
import { formatMoney } from "@/utils/money";

export function ExpensesPage() {
  const navigate = useNavigate();
  const [expenses, setExpenses] = useState<Expense[]>();
  const [error, setError] = useState<string>();
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

  async function handleDelete(expense: Expense) {
    if (!window.confirm(`Delete ${expense.category.name} expense?`)) {
      return;
    }

    setDeletingExpenseId(expense.id);
    setError(undefined);
    try {
      await deleteExpense(expense.id);
      setExpenses((currentExpenses) =>
        currentExpenses?.filter(
          (currentExpense) => currentExpense.id !== expense.id,
        ),
      );
    } catch {
      setError("Expense could not be deleted. Try again in a moment.");
    } finally {
      setDeletingExpenseId(undefined);
    }
  }

  return (
    <PageLayout
      eyebrow="Expenses"
      title="Expenses"
      description="Track spending, review categories, and keep the budget workspace up to date."
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
            <p className="user-management-message">Loading expenses...</p>
          ) : expenses.length === 0 ? (
            <TableEmptyState title="No expenses found" />
          ) : (
            <Table aria-label="Expenses" size="sm">
              <Table.Header>
                <Table.Head id="category" label="Category" isRowHeader />
                <Table.Head id="amount" label="Amount" />
                <Table.Head id="dateTime" label="Date" />
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
                      {formatExpenseDate(expense.dateTime)}
                    </Table.Cell>
                    <Table.Cell mobileLabel="Account">
                      {expense.trackingAccount.name}
                    </Table.Cell>
                    <Table.Cell mobileLabel="Notes">
                      {expense.notes || "-"}
                    </Table.Cell>
                    <Table.Cell mobileRole="actions">
                      <div className="user-management-actions-cell">
                        <Table.MobileDetailsButton
                          label={`Show ${expense.category.name} details`}
                        />
                        <Button
                          aria-label={`Edit ${expense.category.name} expense`}
                          color="tertiary"
                          size="sm"
                          iconLeading={EditActionIcon}
                          onPress={() => navigate(`/expenses/${expense.id}`)}
                        />
                        <Button
                          aria-label={`Delete ${expense.category.name} expense`}
                          color="tertiary"
                          size="sm"
                          iconLeading={DeleteActionIcon}
                          isLoading={deletingExpenseId === expense.id}
                          onPress={() => handleDelete(expense)}
                        />
                      </div>
                    </Table.Cell>
                  </Table.Row>
                ))}
              </Table.Body>
            </Table>
          )}
        </TableCard.Root>
      </section>
    </PageLayout>
  );
}

function formatExpenseDate(dateTime: string) {
  const date = new Date(dateTime);
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

function EditActionIcon(props: ComponentProps<typeof Edit02>) {
  return <Edit02 {...props} data-action-icon="edit" />;
}

function DeleteActionIcon(props: ComponentProps<typeof Trash01>) {
  return <Trash01 {...props} data-action-icon="delete" />;
}
