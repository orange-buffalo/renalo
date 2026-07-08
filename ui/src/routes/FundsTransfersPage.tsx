import { Plus } from "@untitledui/icons";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import { useAppState } from "@/AppState";
import {
  deleteFundsTransfer,
  type FundsTransfer,
  fetchFundsTransfers,
} from "@/api/fundsTransfers";
import { fetchTrackingAccounts } from "@/api/trackingAccounts";
import { ConfirmationDialog } from "@/components/ConfirmationDialog";
import { DateRangeFilter } from "@/components/DateRangeFilter";
import {
  emptyFundsTransferSecondaryFilters,
  type FundsTransferFilterOption,
  FundsTransferMoreFilters,
} from "@/components/FundsTransferMoreFilters";
import { PageLayout } from "@/components/PageLayout";
import { TableEmptyState } from "@/components/TableEmptyState";
import { TableLoadingState } from "@/components/TableLoadingState";
import {
  TableDeleteAction,
  TableEditAction,
  TableRowActions,
} from "@/components/TableRowActions";
import {
  Table,
  TableCard,
} from "@/components/untitled/application/table/table";
import { Button } from "@/components/untitled/base/buttons/button";
import { formatMoney } from "@/utils/money";

export function FundsTransfersPage() {
  const navigate = useNavigate();
  const { transactionDateFilter, setTransactionDateFilter } = useAppState();
  const [transfers, setTransfers] = useState<FundsTransfer[]>();
  const [accounts, setAccounts] = useState<FundsTransferFilterOption[]>([]);
  const [secondaryFilters, setSecondaryFilters] = useState(
    emptyFundsTransferSecondaryFilters,
  );
  const [error, setError] = useState<string>();
  const [confirmingTransfer, setConfirmingTransfer] = useState<FundsTransfer>();
  const [deletingTransferId, setDeletingTransferId] = useState<number>();

  useEffect(() => {
    let isActive = true;
    fetchFundsTransfers(transactionDateFilter, secondaryFilters)
      .then((loadedTransfers) => {
        if (isActive) {
          setTransfers(loadedTransfers);
        }
      })
      .catch(() => {
        if (isActive) {
          setError("Transfers could not be loaded. Try again in a moment.");
        }
      });

    return () => {
      isActive = false;
    };
  }, [transactionDateFilter, secondaryFilters]);

  useEffect(() => {
    let isActive = true;
    fetchTrackingAccounts()
      .then((loadedAccounts) => {
        if (isActive) {
          setAccounts(
            loadedAccounts.map((account) => ({
              id: account.id,
              name: account.name,
            })),
          );
        }
      })
      .catch(() => {
        if (isActive) {
          setError("Transfers could not be loaded. Try again in a moment.");
        }
      });

    return () => {
      isActive = false;
    };
  }, []);

  async function handleDeleteConfirmed() {
    if (!confirmingTransfer) {
      return;
    }

    setDeletingTransferId(confirmingTransfer.id);
    setError(undefined);
    try {
      await deleteFundsTransfer(confirmingTransfer.id);
      setTransfers((currentTransfers) =>
        currentTransfers?.filter(
          (currentTransfer) => currentTransfer.id !== confirmingTransfer.id,
        ),
      );
      setConfirmingTransfer(undefined);
    } catch {
      setError("Transfer could not be deleted. Try again in a moment.");
    } finally {
      setDeletingTransferId(undefined);
    }
  }

  return (
    <PageLayout
      title="Transfers"
      description="Move funds between tracking accounts without creating income or expense rows."
      className="overview-page-surface"
      actions={
        <Button
          color="secondary"
          size="sm"
          iconLeading={Plus}
          onPress={() => navigate("/transfers/create")}
        >
          Add transfer
        </Button>
      }
    >
      <div className="transaction-filter-row">
        <DateRangeFilter
          value={transactionDateFilter}
          onChange={setTransactionDateFilter}
        />
        <FundsTransferMoreFilters
          value={secondaryFilters}
          accounts={accounts}
          onChange={setSecondaryFilters}
        />
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
          {!transfers ? (
            <TableLoadingState label="Loading transfers" />
          ) : transfers.length === 0 ? (
            <TableEmptyState title="No transfers found" />
          ) : (
            <Table aria-label="Transfers" size="sm">
              <Table.Header>
                <Table.Head id="accounts" label="Accounts" isRowHeader />
                <Table.Head id="amount" label="Amount" />
                <Table.Head id="date" label="Date" />
                <Table.Head
                  id="actions"
                  label="Actions"
                  mobileRole="actions"
                  className="[&>div]:justify-end"
                />
              </Table.Header>
              <Table.Body>
                {transfers.map((transfer) => (
                  <Table.Row
                    id={String(transfer.id)}
                    key={transfer.id}
                    data-testid={`funds-transfer-row-${transfer.id}`}
                  >
                    <Table.Cell>
                      <div className="table-row-title">
                        {formatTransferAccounts(transfer)}
                      </div>
                    </Table.Cell>
                    <Table.Cell mobileLabel="Amount">
                      <TransferAmount transfer={transfer} />
                    </Table.Cell>
                    <Table.Cell mobileLabel="Date" mobileRole="detail">
                      {formatTransferDate(transfer.date)}
                    </Table.Cell>
                    <Table.Cell mobileRole="actions">
                      <TableRowActions>
                        <TableEditAction
                          label={`Edit ${transfer.sourceAccount.name} to ${transfer.targetAccount.name} transfer`}
                          onPress={() => navigate(`/transfers/${transfer.id}`)}
                        />
                        <TableDeleteAction
                          label={`Delete ${transfer.sourceAccount.name} to ${transfer.targetAccount.name} transfer`}
                          onPress={() => setConfirmingTransfer(transfer)}
                          isLoading={deletingTransferId === transfer.id}
                        />
                      </TableRowActions>
                    </Table.Cell>
                  </Table.Row>
                ))}
              </Table.Body>
            </Table>
          )}
        </TableCard.Root>
      </section>
      <ConfirmationDialog
        isOpen={Boolean(confirmingTransfer)}
        title={
          confirmingTransfer
            ? `Delete transfer from ${confirmingTransfer.sourceAccount.name} to ${confirmingTransfer.targetAccount.name}?`
            : "Delete transfer?"
        }
        description="This transfer will be removed from your account history."
        confirmLabel="Delete transfer"
        isConfirming={Boolean(deletingTransferId)}
        onCancel={() => setConfirmingTransfer(undefined)}
        onConfirm={handleDeleteConfirmed}
        dataTestId="delete-funds-transfer-overlay"
      />
    </PageLayout>
  );
}

function formatTransferAccounts(transfer: FundsTransfer) {
  return `${transfer.sourceAccount.name} -> ${transfer.targetAccount.name}`;
}

function TransferAmount({ transfer }: { transfer: FundsTransfer }) {
  const sourceAmount = formatMoney(
    transfer.sourceAmountMinor,
    transfer.sourceAccount.currency,
  );
  if (transfer.sourceAccount.currency === transfer.targetAccount.currency) {
    return sourceAmount;
  }

  return (
    <span className="funds-transfer-cross-currency-amount">
      <span>{sourceAmount}</span>
      <span aria-hidden="true">→</span>
      <span>
        {formatMoney(
          transfer.targetAmountMinor,
          transfer.targetAccount.currency,
        )}
      </span>
    </span>
  );
}

function formatTransferDate(isoDate: string) {
  const transferDate = startOfLocalDay(parseIsoDate(isoDate));
  const today = startOfLocalDay(new Date());
  const yesterday = new Date(today);
  yesterday.setDate(yesterday.getDate() - 1);

  if (transferDate.getTime() === today.getTime()) {
    return "Today";
  }
  if (transferDate.getTime() === yesterday.getTime()) {
    return "Yesterday";
  }

  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
  }).format(transferDate);
}

function parseIsoDate(isoDate: string) {
  const [year, month, day] = isoDate.split("-").map(Number);
  return new Date(year, month - 1, day);
}

function startOfLocalDay(date: Date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate());
}
