import { Plus } from "@untitledui/icons";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import {
  deleteTransaction,
  fetchTransactions,
  type RecurringTransactionDeleteScope,
  type Transaction,
  type TransactionApiConfig,
} from "@/api/transactions";
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

export type TransactionsPageConfig = {
  api: TransactionApiConfig;
  routeBasePath: string;
  title: string;
  description: string;
  addLabel: string;
  loadingLabel: string;
  emptyTitle: string;
  tableLabel: string;
  categoryColumnLabel: string;
  itemLabel: string;
  deleteTitle: (transaction: Transaction) => string;
  deleteDescription: string;
  deleteConfirmLabel: string;
  deleteError: string;
  loadError: string;
  rowTestIdPrefix: string;
  deleteDialogDataTestId: string;
};

export function TransactionsPage({
  config,
}: {
  config: TransactionsPageConfig;
}) {
  const navigate = useNavigate();
  const [transactions, setTransactions] = useState<Transaction[]>();
  const [error, setError] = useState<string>();
  const [confirmingTransaction, setConfirmingTransaction] =
    useState<Transaction>();
  const [recurringDeleteScope, setRecurringDeleteScope] =
    useState<RecurringTransactionDeleteScope>("THIS_OCCURRENCE_ONLY");
  const [deletingTransactionId, setDeletingTransactionId] = useState<number>();

  useEffect(() => {
    let isActive = true;
    fetchTransactions(config.api)
      .then((loadedTransactions) => {
        if (isActive) {
          setTransactions(loadedTransactions);
        }
      })
      .catch(() => {
        if (isActive) {
          setError(config.loadError);
        }
      });

    return () => {
      isActive = false;
    };
  }, [config]);

  async function handleDeleteConfirmed() {
    if (!confirmingTransaction) {
      return;
    }

    setDeletingTransactionId(confirmingTransaction.id);
    setError(undefined);
    try {
      await deleteTransaction(
        config.api,
        confirmingTransaction.id,
        confirmingTransaction.recurrence ? recurringDeleteScope : undefined,
      );
      if (confirmingTransaction.recurrence) {
        setTransactions(await fetchTransactions(config.api));
      } else {
        setTransactions((currentTransactions) =>
          currentTransactions?.filter(
            (currentTransaction) =>
              currentTransaction.id !== confirmingTransaction.id,
          ),
        );
      }
      setConfirmingTransaction(undefined);
    } catch {
      setError(config.deleteError);
    } finally {
      setDeletingTransactionId(undefined);
    }
  }

  return (
    <PageLayout
      title={config.title}
      description={config.description}
      actions={
        <Button
          color="tertiary"
          size="sm"
          iconLeading={Plus}
          onPress={() => navigate(`${config.routeBasePath}/create`)}
        >
          {config.addLabel}
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
          {!transactions ? (
            <TableLoadingState label={config.loadingLabel} />
          ) : transactions.length === 0 ? (
            <TableEmptyState title={config.emptyTitle} />
          ) : (
            <Table aria-label={config.tableLabel} size="sm">
              <Table.Header>
                <Table.Head
                  id="category"
                  label={config.categoryColumnLabel}
                  isRowHeader
                />
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
                {transactions.map((transaction) => (
                  <Table.Row
                    id={transaction.id}
                    key={transaction.id}
                    data-testid={`${config.rowTestIdPrefix}-${transaction.id}`}
                  >
                    <Table.Cell>{transaction.category.name}</Table.Cell>
                    <Table.Cell>
                      {formatMoney(
                        transaction.amountMinor,
                        transaction.trackingAccount.currency,
                      )}
                    </Table.Cell>
                    <Table.Cell mobileLabel="Date" mobileRole="detail">
                      <span>{formatTransactionDate(transaction.date)}</span>
                      {transaction.recurrence &&
                        isActiveRecurrence(transaction) && (
                          <span className="transaction-recurrence-description">
                            {transaction.recurrence.description}
                          </span>
                        )}
                    </Table.Cell>
                    <Table.Cell mobileLabel="Account">
                      {transaction.trackingAccount.name}
                    </Table.Cell>
                    <Table.Cell mobileLabel="Notes">
                      {transaction.notes || "-"}
                    </Table.Cell>
                    <Table.Cell mobileRole="actions">
                      <TableRowActions>
                        <TableMobileDetailsAction
                          label={`Show ${transaction.category.name} details`}
                        />
                        <TableEditAction
                          label={`Edit ${transaction.category.name} ${config.itemLabel}`}
                          onPress={() =>
                            navigate(
                              `${config.routeBasePath}/${transaction.id}`,
                            )
                          }
                        />
                        <TableDeleteAction
                          label={`Delete ${transaction.category.name} ${config.itemLabel}`}
                          isLoading={deletingTransactionId === transaction.id}
                          onPress={() => {
                            setRecurringDeleteScope("THIS_OCCURRENCE_ONLY");
                            setConfirmingTransaction(transaction);
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

        {confirmingTransaction && (
          <ConfirmationDialog
            dataTestId={config.deleteDialogDataTestId}
            isOpen={Boolean(confirmingTransaction)}
            title={config.deleteTitle(confirmingTransaction)}
            description={config.deleteDescription}
            confirmLabel={config.deleteConfirmLabel}
            isConfirming={deletingTransactionId === confirmingTransaction.id}
            onCancel={() => setConfirmingTransaction(undefined)}
            onConfirm={handleDeleteConfirmed}
          >
            {confirmingTransaction.recurrence && (
              <>
                <p className="transaction-recurrence-delete-context">
                  {formatRecurringDeleteContext(
                    confirmingTransaction,
                    config.itemLabel,
                  )}
                </p>
                <RadioGroup
                  aria-label="Delete scope"
                  value={recurringDeleteScope}
                  onChange={(scope) =>
                    setRecurringDeleteScope(
                      scope as RecurringTransactionDeleteScope,
                    )
                  }
                >
                  <RadioButton
                    value="THIS_OCCURRENCE_ONLY"
                    label="This occurrence only"
                    hint={`Delete only this generated ${config.itemLabel}.`}
                  />
                  <RadioButton
                    value="THIS_AND_ALL_FOLLOWING_OCCURRENCES"
                    label="This and all following occurrences"
                    hint={`Delete this ${config.itemLabel} and every later ${config.itemLabel} in the series.`}
                  />
                  <RadioButton
                    value="ALL_OCCURRENCES"
                    label="All occurrences"
                    hint={`Delete the entire recurring ${config.itemLabel} series.`}
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

function formatTransactionDate(isoDate: string) {
  const [year, month, day] = isoDate.split("-").map(Number);
  const date = new Date(year, month - 1, day);
  const today = startOfLocalDay(new Date());
  const transactionDay = startOfLocalDay(date);
  const dayDifference = Math.round(
    (today.getTime() - transactionDay.getTime()) / 86_400_000,
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

function isActiveRecurrence(transaction: Transaction) {
  const endDate = transaction.recurrence?.endDate;
  if (!endDate) {
    return true;
  }

  return startOfLocalDay(parseIsoDate(endDate)) > startOfLocalDay(new Date());
}

function parseIsoDate(isoDate: string) {
  const [year, month, day] = isoDate.split("-").map(Number);
  return new Date(year, month - 1, day);
}

function formatRecurringDeleteContext(
  transaction: Transaction,
  itemLabel: string,
) {
  const recurrence = transaction.recurrence;
  if (!recurrence) {
    return "";
  }

  const startDate = formatShortDate(recurrence.startDate);
  if (!recurrence.endDate) {
    return `This ${itemLabel} is part of the repeated series starting ${startDate} with no end date.`;
  }

  return `This ${itemLabel} is part of the repeated series starting ${startDate} and ending ${formatShortDate(recurrence.endDate)}.`;
}

function formatShortDate(isoDate: string) {
  return new Intl.DateTimeFormat("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
  }).format(parseIsoDate(isoDate));
}
