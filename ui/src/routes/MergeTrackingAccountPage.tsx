import { type FormEvent, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router";
import {
  fetchTrackingAccountMergeSummary,
  mergeTrackingAccount,
  type TrackingAccount,
  type TrackingAccountMergeSummary,
} from "@/api/trackingAccounts";
import { FormLoadingOverlay } from "@/components/FormLoadingOverlay";
import { PageLayout } from "@/components/PageLayout";
import { SearchableDropdown } from "@/components/SearchableDropdown";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { Button } from "@/components/untitled/base/buttons/button";
import { formatMoney } from "@/utils/money";

const mergeDetailsLoadError =
  "Account merge details could not be loaded. Try again in a moment.";

export function MergeTrackingAccountPage() {
  const navigate = useNavigate();
  const { accountId } = useParams();
  const [summary, setSummary] = useState<TrackingAccountMergeSummary>();
  const [targetAccountId, setTargetAccountId] = useState<number>();
  const [error, setError] = useState<string>();
  const [targetError, setTargetError] = useState<string>();
  const [isLoading, setIsLoading] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);

  useEffect(() => {
    if (!accountId) {
      return;
    }

    let isActive = true;
    fetchTrackingAccountMergeSummary(Number(accountId))
      .then((loadedSummary) => {
        if (!isActive) {
          return;
        }
        setError(undefined);
        setSummary(loadedSummary);
        setTargetAccountId(loadedSummary.targetAccounts[0]?.id);
        setIsLoading(false);
      })
      .catch(() => {
        if (isActive) {
          setError(mergeDetailsLoadError);
          setIsLoading(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, [accountId]);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!accountId || !summary) {
      return;
    }
    if (!targetAccountId) {
      setTargetError("Choose the account to merge into.");
      return;
    }

    setIsSubmitting(true);
    setError(undefined);
    setTargetError(undefined);
    try {
      await mergeTrackingAccount(Number(accountId), targetAccountId);
      navigate("/settings", { replace: true });
    } catch {
      setError(
        "Account could not be merged. Check the selected account and try again.",
      );
    } finally {
      setIsSubmitting(false);
    }
  }

  const targetAccounts = summary?.targetAccounts ?? [];
  const selectedTargetAccount = targetAccounts.find(
    (account) => account.id === targetAccountId,
  );
  const hasCompatibleTarget = targetAccounts.length > 0;
  const visibleError =
    error && (!summary || error !== mergeDetailsLoadError) ? error : undefined;

  return (
    <PageLayout
      title={summary ? `Merge ${summary.sourceAccount.name}` : "Merge account"}
      description="Move all activity from one tracking account into another account."
    >
      <section className="standard-page-panel tracking-account-panel form-loading-container">
        <form className="tracking-account-form" onSubmit={handleSubmit}>
          {visibleError && (
            <Alert
              tone="error"
              title={visibleError}
              className="tracking-account-form-wide"
            />
          )}
          {summary && (
            <>
              <Alert
                tone="warning"
                title="Account merges are irreversible."
                className="tracking-account-form-wide"
              >
                <p>
                  The selected account will receive all linked entries and the
                  current account will be deleted after the merge.
                </p>
              </Alert>
              <div className="tracking-account-form-wide account-merge-summary">
                <AccountMergeStat
                  label="Expenses"
                  value={summary.expensesCount}
                />
                <AccountMergeStat label="Income" value={summary.incomesCount} />
                <AccountMergeStat
                  label="Transfers"
                  value={summary.transfersCount}
                />
              </div>
              {!hasCompatibleTarget && (
                <Alert
                  tone="error"
                  title="No compatible account available"
                  className="tracking-account-form-wide"
                >
                  <p>
                    Create another {summary.sourceAccount.currency} account
                    before merging this account.
                  </p>
                </Alert>
              )}
              <TargetAccountDropdown
                accounts={targetAccounts}
                selectedAccountId={targetAccountId}
                isInvalid={Boolean(targetError)}
                error={targetError}
                onChange={(nextAccountId) => {
                  setTargetAccountId(nextAccountId);
                  setTargetError(undefined);
                }}
              />
              <div className="tracking-account-form-wide account-merge-target-summary">
                {selectedTargetAccount ? (
                  <p>
                    <strong>{summary.sourceAccount.name}</strong> will be merged
                    into <strong>{selectedTargetAccount.name}</strong>. The
                    target account initial balance will become{" "}
                    {formatMoney(
                      summary.sourceAccount.initialBalanceMinor +
                        selectedTargetAccount.initialBalanceMinor,
                      summary.sourceAccount.currency,
                    )}
                    .
                  </p>
                ) : (
                  <p>
                    No other {summary.sourceAccount.currency} account is
                    available.
                  </p>
                )}
              </div>
            </>
          )}
          <div className="tracking-account-actions">
            <Button
              color="tertiary"
              size="sm"
              onPress={() => navigate("/settings")}
              isDisabled={isSubmitting}
            >
              Cancel
            </Button>
            <Button
              color="primary-destructive"
              size="sm"
              type="submit"
              isLoading={isSubmitting}
              isDisabled={!summary || targetAccounts.length === 0}
            >
              Merge account
            </Button>
          </div>
        </form>
        <FormLoadingOverlay isLoading={isLoading} />
      </section>
    </PageLayout>
  );
}

function AccountMergeStat({ label, value }: { label: string; value: number }) {
  return (
    <div className="account-merge-stat">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

type TargetAccountDropdownProps = {
  accounts: TrackingAccount[];
  selectedAccountId?: number;
  isInvalid: boolean;
  error?: string;
  onChange: (accountId: number) => void;
};

function TargetAccountDropdown({
  accounts,
  selectedAccountId,
  isInvalid,
  error,
  onChange,
}: TargetAccountDropdownProps) {
  return (
    <SearchableDropdown
      label="Merge into account"
      placeholder="Choose an account"
      selectedKey={selectedAccountId ? String(selectedAccountId) : undefined}
      isDisabled={accounts.length === 0}
      isInvalid={isInvalid}
      hint={error}
      items={accounts.map((account) => ({
        id: String(account.id),
        label: account.name,
        supportingText: `${account.currency} · ${formatMoney(
          account.initialBalanceMinor,
          account.currency,
        )} initial`,
      }))}
      className="searchable-dropdown-field account-merge-target-field"
      onSelectionChange={(key) => onChange(Number(key))}
    />
  );
}
