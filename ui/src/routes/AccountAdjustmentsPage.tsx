import { Scales01, Trash01 } from "@untitledui/icons";
import { type FormEvent, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router";
import {
  type AccountAdjustmentsData,
  createAccountAdjustment,
  deleteAccountAdjustment,
  fetchAccountAdjustments,
} from "@/api/accountAdjustments";
import { ConfirmationDialog } from "@/components/ConfirmationDialog";
import { FormLoadingOverlay } from "@/components/FormLoadingOverlay";
import { MoneyInput } from "@/components/MoneyInput";
import { PageLayout } from "@/components/PageLayout";
import { TableEmptyState } from "@/components/TableEmptyState";
import { TableLoadingState } from "@/components/TableLoadingState";
import { Alert } from "@/components/untitled/application/alerts/alert";
import {
  Table,
  TableCard,
} from "@/components/untitled/application/table/table";
import { Button } from "@/components/untitled/base/buttons/button";
import { formatMoney, parseMoneyInput } from "@/utils/money";

export function AccountAdjustmentsPage() {
  const navigate = useNavigate();
  const { accountId } = useParams();
  const [data, setData] = useState<AccountAdjustmentsData>();
  const [amount, setAmount] = useState("");
  const [error, setError] = useState<string>();
  const [amountError, setAmountError] = useState<string>();
  const [isLoading, setIsLoading] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [confirmingDeleteId, setConfirmingDeleteId] = useState<number>();
  const [deletingId, setDeletingId] = useState<number>();

  useEffect(() => {
    if (!accountId) {
      return;
    }

    let isActive = true;
    fetchAccountAdjustments(Number(accountId))
      .then((loadedData) => {
        if (isActive) {
          setData(loadedData);
          setIsLoading(false);
        }
      })
      .catch(() => {
        if (isActive) {
          setError("Adjustments could not be loaded. Try again in a moment.");
          setIsLoading(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, [accountId]);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!accountId || !data) {
      return;
    }

    const adjustmentAmountMinor = parseMoneyInput(amount, data.currency);
    const nextAmountError =
      adjustmentAmountMinor === undefined || adjustmentAmountMinor === 0
        ? "Enter a valid non-zero amount."
        : undefined;
    setAmountError(nextAmountError);
    if (adjustmentAmountMinor === undefined || adjustmentAmountMinor === 0) {
      return;
    }

    setIsSubmitting(true);
    setError(undefined);

    try {
      await createAccountAdjustment(Number(accountId), adjustmentAmountMinor);
      setAmount("");
      const updatedData = await fetchAccountAdjustments(Number(accountId));
      setData(updatedData);
    } catch {
      setError("Adjustment could not be saved. Try again in a moment.");
    } finally {
      setIsSubmitting(false);
    }
  }

  async function handleDeleteConfirmed() {
    if (!accountId || !confirmingDeleteId || !data) {
      return;
    }

    setDeletingId(confirmingDeleteId);
    setError(undefined);
    try {
      await deleteAccountAdjustment(Number(accountId), confirmingDeleteId);
      const updatedData = await fetchAccountAdjustments(Number(accountId));
      setData(updatedData);
      setConfirmingDeleteId(undefined);
    } catch {
      setError("Adjustment could not be deleted. Try again in a moment.");
    } finally {
      setDeletingId(undefined);
    }
  }

  return (
    <PageLayout
      title={data ? `Adjustments — ${data.accountName}` : "Account adjustments"}
      description={
        data
          ? `Balance: ${formatMoney(data.currentBalanceMinor, data.currency)}`
          : "Manage balance adjustments for this account."
      }
    >
      <section className="standard-page-panel tracking-account-panel form-loading-container">
        {error && (
          <Alert
            tone="error"
            title={error}
            className="tracking-account-form-wide"
          />
        )}
        <form className="tracking-account-form" onSubmit={handleSubmit}>
          <MoneyInput
            label="Adjustment amount"
            name="adjustmentAmount"
            value={amount}
            currency={data?.currency ?? "AUD"}
            isInvalid={Boolean(amountError)}
            hint={amountError ?? "Positive for credit, negative for debit."}
            onChange={(nextAmount) => {
              setAmount(nextAmount);
              setAmountError(undefined);
            }}
          />
          <div className="tracking-account-form-spacer" />
          <div className="tracking-account-actions">
            <Button
              color="tertiary"
              size="sm"
              onPress={() => navigate("/settings")}
              isDisabled={isSubmitting}
            >
              Back to settings
            </Button>
            <Button
              color="primary"
              size="sm"
              type="submit"
              isLoading={isSubmitting}
              iconLeading={Scales01}
            >
              Add adjustment
            </Button>
          </div>
        </form>
        <FormLoadingOverlay isLoading={isLoading} />
      </section>

      <section className="standard-page-panel user-management-panel">
        <TableCard.Root size="sm">
          {!data ? (
            <TableLoadingState label="Loading adjustments" />
          ) : (data.adjustments ?? []).length === 0 ? (
            <TableEmptyState title="No adjustments yet" />
          ) : (
            <Table aria-label="Account adjustments" size="sm">
              <Table.Header>
                <Table.Head id="amount" label="Amount" isRowHeader />
                <Table.Head
                  id="actions"
                  label="Actions"
                  mobileRole="actions"
                  className="[&>div]:justify-end"
                />
              </Table.Header>
              <Table.Body>
                {(data.adjustments ?? []).map((adjustment) => (
                  <Table.Row
                    id={String(adjustment.id)}
                    key={adjustment.id}
                    data-testid={`adjustment-row-${adjustment.id}`}
                  >
                    <Table.Cell>
                      <span
                        className={
                          adjustment.adjustmentAmountMinor >= 0
                            ? "text-green-600"
                            : "text-red-600"
                        }
                      >
                        {formatMoney(
                          adjustment.adjustmentAmountMinor,
                          data.currency,
                        )}
                      </span>
                    </Table.Cell>
                    <Table.Cell mobileRole="actions">
                      <div className="table-row-actions">
                        <Button
                          aria-label={`Delete adjustment of ${formatMoney(adjustment.adjustmentAmountMinor, data.currency)}`}
                          color="tertiary-destructive"
                          size="sm"
                          iconLeading={Trash01}
                          isLoading={deletingId === adjustment.id}
                          onPress={() => setConfirmingDeleteId(adjustment.id)}
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

      <ConfirmationDialog
        isOpen={Boolean(confirmingDeleteId)}
        title="Delete adjustment?"
        description="This adjustment will be removed from your account history. The account balance will be recalculated."
        confirmLabel="Delete adjustment"
        isConfirming={Boolean(deletingId)}
        onCancel={() => setConfirmingDeleteId(undefined)}
        onConfirm={handleDeleteConfirmed}
      />
    </PageLayout>
  );
}
