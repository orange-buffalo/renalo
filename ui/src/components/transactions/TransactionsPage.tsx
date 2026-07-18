import { LayersThree01, Plus } from "@untitledui/icons";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import { useAppState } from "@/AppState";
import { fetchTrackingAccounts } from "@/api/trackingAccounts";
import {
  deleteTransaction,
  fetchTransactions,
  type RecurringTransactionDeleteScope,
  type Transaction,
  type TransactionApiConfig,
} from "@/api/transactions";
import { ConfirmationDialog } from "@/components/ConfirmationDialog";
import { DateRangeFilter } from "@/components/DateRangeFilter";
import { PageLayout } from "@/components/PageLayout";
import { TableEmptyState } from "@/components/TableEmptyState";
import { TableLoadingState } from "@/components/TableLoadingState";
import {
  TableDeleteAction,
  TableEditAction,
  TableRowActions,
  TableViewAction,
} from "@/components/TableRowActions";
import {
  emptyTransactionSecondaryFilters,
  type TransactionFilterOption,
  TransactionMoreFilters,
  type TransactionSecondaryFilters,
} from "@/components/TransactionMoreFilters";
import {
  Table,
  TableCard,
} from "@/components/untitled/application/table/table";
import { Button } from "@/components/untitled/base/buttons/button";
import { Dropdown } from "@/components/untitled/base/dropdown/dropdown";
import {
  RadioButton,
  RadioGroup,
} from "@/components/untitled/base/radio-buttons/radio-buttons";
import { formatMoney, formatMoneyInput } from "@/utils/money";

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
  fetchCategories: () => Promise<TransactionFilterOption[]>;
  itemLabel: string;
  deleteTitle: (transaction: Transaction) => string;
  deleteDescription: string;
  deleteConfirmLabel: string;
  deleteError: string;
  loadError: string;
  plannedGroupLabel: string;
  rowTestIdPrefix: string;
  deleteDialogDataTestId: string;
  groupingStorageKey: string;
};

type TransactionGrouping = "plain" | "date" | "category";

const groupingOptions: { id: TransactionGrouping; label: string }[] = [
  { id: "plain", label: "Plain list" },
  { id: "date", label: "Group by date" },
  { id: "category", label: "Group by category" },
];

export function TransactionsPage({
  config,
}: {
  config: TransactionsPageConfig;
}) {
  const navigate = useNavigate();
  const { transactionDateFilter, setTransactionDateFilter } = useAppState();
  const [transactions, setTransactions] = useState<Transaction[]>();
  const [error, setError] = useState<string>();
  const [categories, setCategories] = useState<TransactionFilterOption[]>([]);
  const [accounts, setAccounts] = useState<TransactionFilterOption[]>([]);
  const [secondaryFilters, setSecondaryFilters] =
    useState<TransactionSecondaryFilters>(emptyTransactionSecondaryFilters);
  const [confirmingTransaction, setConfirmingTransaction] =
    useState<Transaction>();
  const [recurringDeleteScope, setRecurringDeleteScope] =
    useState<RecurringTransactionDeleteScope>("THIS_OCCURRENCE_ONLY");
  const [deletingTransactionId, setDeletingTransactionId] = useState<number>();
  const [showPlannedTransactions, setShowPlannedTransactions] = useState(false);
  const [grouping, setGrouping] = useState<TransactionGrouping>(() =>
    loadTransactionGrouping(config.groupingStorageKey),
  );

  const transactionRows = transactions
    ? groupTransactionRows(
        groupPlannedTransactions(transactions, showPlannedTransactions),
        grouping,
      )
    : undefined;

  function changeGrouping(nextGrouping: TransactionGrouping) {
    setGrouping(nextGrouping);
    window.localStorage.setItem(config.groupingStorageKey, nextGrouping);
  }

  useEffect(() => {
    let isActive = true;
    setShowPlannedTransactions(false);
    fetchTransactions(config.api, transactionDateFilter, secondaryFilters)
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
  }, [config, transactionDateFilter, secondaryFilters]);

  useEffect(() => {
    let isActive = true;
    Promise.all([config.fetchCategories(), fetchTrackingAccounts()])
      .then(([loadedCategories, loadedAccounts]) => {
        if (!isActive) {
          return;
        }
        setCategories(loadedCategories);
        setAccounts(
          loadedAccounts.map((account) => ({
            id: account.id,
            name: account.name,
          })),
        );
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
        setTransactions(
          await fetchTransactions(
            config.api,
            transactionDateFilter,
            secondaryFilters,
          ),
        );
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
      className="overview-page-surface"
      actions={
        <Button
          color="secondary"
          size="sm"
          iconLeading={Plus}
          onPress={() => navigate(`${config.routeBasePath}/create`)}
        >
          {config.addLabel}
        </Button>
      }
    >
      <div className="transaction-filter-row">
        <DateRangeFilter
          value={transactionDateFilter}
          onChange={setTransactionDateFilter}
        />
        <div className="transaction-filter-actions">
          <TransactionGroupingSelector
            value={grouping}
            onChange={changeGrouping}
          />
          <TransactionMoreFilters
            value={secondaryFilters}
            categories={categories}
            accounts={accounts}
            categoryLabel={config.categoryColumnLabel}
            onChange={setSecondaryFilters}
          />
        </div>
      </div>
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
                {transactionRows?.map((row) =>
                  row.kind === "group" ? (
                    <Table.Row
                      id={`group-${row.key}`}
                      key={`group-${row.key}`}
                      className="transaction-group-row"
                      data-testid={`${config.itemLabel}-group-${row.key}`}
                    >
                      <Table.Cell colSpan={6}>
                        <div className="transaction-group-heading">
                          <span>{row.label}</span>
                          <span className="transaction-group-amounts">
                            {row.amounts
                              .map(
                                (amount) =>
                                  `${formatMoneyInput(amount.amountMinor, amount.currency)} ${amount.currency}`,
                              )
                              .join(", ")}
                          </span>
                        </div>
                      </Table.Cell>
                    </Table.Row>
                  ) : row.kind === "planned" ? (
                    <Table.Row
                      id="planned-transactions"
                      key="planned-transactions"
                      data-testid={`${config.rowTestIdPrefix}-planned`}
                    >
                      <Table.Cell>
                        <span className="transaction-planned-label">
                          {config.plannedGroupLabel}
                        </span>
                      </Table.Cell>
                      <Table.Cell>
                        <div className="transaction-planned-amounts">
                          {row.amounts.map((amount) => (
                            <span key={amount.currency}>
                              {formatMoney(amount.amountMinor, amount.currency)}
                            </span>
                          ))}
                        </div>
                      </Table.Cell>
                      <Table.Cell mobileLabel="Date" mobileRole="detail" />
                      <Table.Cell mobileLabel="Account" />
                      <Table.Cell mobileLabel="Notes" />
                      <Table.Cell mobileRole="actions">
                        <TableRowActions>
                          <TableViewAction
                            label={`View all ${config.plannedGroupLabel.toLowerCase()}`}
                            onPress={() => setShowPlannedTransactions(true)}
                          />
                        </TableRowActions>
                      </Table.Cell>
                    </Table.Row>
                  ) : (
                    <Table.Row
                      id={row.transaction.id}
                      key={row.transaction.id}
                      data-testid={`${config.rowTestIdPrefix}-${row.transaction.id}`}
                    >
                      <Table.Cell>{row.transaction.category.name}</Table.Cell>
                      <Table.Cell>
                        {formatMoney(
                          row.transaction.amountMinor,
                          row.transaction.trackingAccount.currency,
                        )}
                      </Table.Cell>
                      <Table.Cell
                        mobileLabel="Date"
                        mobileRole="visible-detail"
                      >
                        <span>
                          {formatTransactionDate(row.transaction.date)}
                        </span>
                        {row.transaction.recurrence &&
                          isActiveRecurrence(row.transaction) && (
                            <span className="transaction-recurrence-description">
                              {row.transaction.recurrence.description}
                            </span>
                          )}
                      </Table.Cell>
                      <Table.Cell mobileLabel="Account">
                        {row.transaction.trackingAccount.name}
                      </Table.Cell>
                      <Table.Cell mobileLabel="Notes">
                        {row.transaction.notes || "-"}
                      </Table.Cell>
                      <Table.Cell mobileRole="actions">
                        <TableRowActions>
                          <TableEditAction
                            label={`Edit ${row.transaction.category.name} ${config.itemLabel}`}
                            onPress={() =>
                              navigate(
                                `${config.routeBasePath}/${row.transaction.id}`,
                              )
                            }
                          />
                          <TableDeleteAction
                            label={`Delete ${row.transaction.category.name} ${config.itemLabel}`}
                            isLoading={
                              deletingTransactionId === row.transaction.id
                            }
                            onPress={() => {
                              setRecurringDeleteScope("THIS_OCCURRENCE_ONLY");
                              setConfirmingTransaction(row.transaction);
                            }}
                          />
                        </TableRowActions>
                      </Table.Cell>
                    </Table.Row>
                  ),
                )}
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

type TransactionRow =
  | { kind: "transaction"; transaction: Transaction }
  | { kind: "planned"; amounts: PlannedAmount[] }
  | { kind: "group"; key: string; label: string; amounts: PlannedAmount[] };

type PlannedAmount = {
  currency: string;
  amountMinor: number;
};

function groupPlannedTransactions(
  transactions: Transaction[],
  showPlannedTransactions: boolean,
): TransactionRow[] {
  if (showPlannedTransactions) {
    return transactions.map((transaction) => ({
      kind: "transaction",
      transaction,
    }));
  }

  const today = startOfLocalDay(new Date());
  const plannedTransactions = transactions.filter(
    (transaction) => startOfLocalDay(parseIsoDate(transaction.date)) > today,
  );

  if (plannedTransactions.length === 0) {
    return transactions.map((transaction) => ({
      kind: "transaction",
      transaction,
    }));
  }

  const amountsByCurrency = new Map<string, number>();
  for (const transaction of plannedTransactions) {
    const currency = transaction.trackingAccount.currency;
    amountsByCurrency.set(
      currency,
      (amountsByCurrency.get(currency) ?? 0) + transaction.amountMinor,
    );
  }

  return [
    {
      kind: "planned",
      amounts: Array.from(amountsByCurrency, ([currency, amountMinor]) => ({
        currency,
        amountMinor,
      })),
    },
    ...transactions
      .filter((transaction) => !plannedTransactions.includes(transaction))
      .map((transaction) => ({ kind: "transaction", transaction }) as const),
  ];
}

function groupTransactionRows(
  rows: TransactionRow[],
  grouping: TransactionGrouping,
): TransactionRow[] {
  if (grouping === "plain") {
    return rows;
  }

  const result: TransactionRow[] = rows.filter((row) => row.kind === "planned");
  const groups = new Map<
    string,
    { label: string; transactions: Transaction[] }
  >();

  for (const row of rows) {
    if (row.kind !== "transaction") {
      continue;
    }

    const key =
      grouping === "date"
        ? row.transaction.date
        : String(row.transaction.category.id);
    const group = groups.get(key) ?? {
      label:
        grouping === "date"
          ? formatTransactionDate(row.transaction.date)
          : row.transaction.category.name,
      transactions: [],
    };
    group.transactions.push(row.transaction);
    groups.set(key, group);
  }

  for (const [key, group] of groups) {
    const amountsByCurrency = new Map<string, number>();
    for (const transaction of group.transactions) {
      const currency = transaction.trackingAccount.currency;
      amountsByCurrency.set(
        currency,
        (amountsByCurrency.get(currency) ?? 0) + transaction.amountMinor,
      );
    }
    result.push({
      kind: "group",
      key: `${grouping}-${key}`,
      label: group.label,
      amounts: Array.from(amountsByCurrency, ([currency, amountMinor]) => ({
        currency,
        amountMinor,
      })),
    });
    result.push(
      ...group.transactions.map(
        (transaction) => ({ kind: "transaction", transaction }) as const,
      ),
    );
  }

  return result;
}

function TransactionGroupingSelector({
  value,
  onChange,
}: {
  value: TransactionGrouping;
  onChange: (value: TransactionGrouping) => void;
}) {
  const selectedOption = groupingOptions.find((option) => option.id === value);
  if (!selectedOption) {
    return null;
  }

  return (
    <Dropdown.Root>
      <Button
        aria-label={`Grouping: ${selectedOption.label}`}
        color="tertiary"
        size="sm"
        iconLeading={LayersThree01}
      />
      <Dropdown.Popover className="w-52" placement="bottom right">
        <Dropdown.Menu
          aria-label="Transaction grouping"
          selectionMode="single"
          selectedKeys={[value]}
          onAction={(key) => onChange(String(key) as TransactionGrouping)}
        >
          {groupingOptions.map((option) => (
            <Dropdown.Item
              key={option.id}
              id={option.id}
              label={option.label}
            />
          ))}
        </Dropdown.Menu>
      </Dropdown.Popover>
    </Dropdown.Root>
  );
}

function loadTransactionGrouping(storageKey: string): TransactionGrouping {
  const storedValue = window.localStorage.getItem(storageKey);
  return groupingOptions.some((option) => option.id === storedValue)
    ? (storedValue as TransactionGrouping)
    : "date";
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
