import { useEffect, useState } from "react";
import {
  type AccountDashboardSummary,
  fetchAccountDashboardSummaries,
} from "@/api/dashboard";
import { PageLayout } from "@/components/PageLayout";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { LoadingIndicator } from "@/components/untitled/application/loading-indicator/loading-indicator";
import { formatMoney } from "@/utils/money";

const currentMonthName = new Intl.DateTimeFormat(undefined, {
  month: "long",
}).format(new Date());

export function TrackingPage() {
  const [accountSummaries, setAccountSummaries] = useState<
    AccountDashboardSummary[]
  >([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(false);

  useEffect(() => {
    let isActive = true;

    fetchAccountDashboardSummaries()
      .then((summaries) => {
        if (!isActive) {
          return;
        }
        setAccountSummaries(summaries);
        setError(false);
      })
      .catch(() => {
        if (!isActive) {
          return;
        }
        setError(true);
      })
      .finally(() => {
        if (isActive) {
          setIsLoading(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, []);

  return (
    <PageLayout
      title="Dashboard"
      description="Review account balances and current-month money flow."
    >
      {error && (
        <Alert tone="error" title="Dashboard could not be loaded">
          <p>Refresh the page to try again.</p>
        </Alert>
      )}

      {isLoading && (
        <section
          className="standard-page-panel dashboard-loading-panel"
          role="status"
          aria-busy="true"
          aria-label="Loading dashboard"
        >
          <LoadingIndicator size="md" />
        </section>
      )}

      {!isLoading && !error && accountSummaries.length === 0 && (
        <section className="standard-page-panel dashboard-empty-panel">
          <h2>No tracking accounts yet</h2>
          <p>Create a tracking account to see dashboard balances.</p>
        </section>
      )}

      {!isLoading && !error && accountSummaries.length > 0 && (
        <section
          className="dashboard-account-grid"
          aria-label="Account balances"
        >
          {accountSummaries.map((summary) => (
            <AccountSummaryCard key={summary.accountId} summary={summary} />
          ))}
        </section>
      )}
    </PageLayout>
  );
}

function AccountSummaryCard({ summary }: { summary: AccountDashboardSummary }) {
  return (
    <article
      className="dashboard-account-card"
      data-testid="dashboard-account-card"
    >
      <div className="dashboard-account-card-header">
        <h2>{summary.accountName}</h2>
      </div>

      <div>
        <p className="dashboard-account-balance-label">Total balance</p>
        <p className="dashboard-account-balance">
          {formatMoney(summary.totalBalanceMinor, summary.currency)}
        </p>
      </div>

      <div className="dashboard-money-flow-row">
        <MoneyFlowMetric
          label={`Inflow ${currentMonthName}`}
          amountMinor={summary.currentMonthInflowMinor}
          currency={summary.currency}
          tone="positive"
        />
        <MoneyFlowMetric
          label={`Outflow ${currentMonthName}`}
          amountMinor={summary.currentMonthOutflowMinor}
          currency={summary.currency}
          tone="negative"
        />
      </div>
    </article>
  );
}

function MoneyFlowMetric({
  label,
  amountMinor,
  currency,
  tone,
}: {
  label: string;
  amountMinor: number;
  currency: string;
  tone: "positive" | "negative";
}) {
  return (
    <div className={`dashboard-money-flow dashboard-money-flow--${tone}`}>
      <span>{label}</span>
      <strong>{formatMoney(amountMinor, currency)}</strong>
    </div>
  );
}
