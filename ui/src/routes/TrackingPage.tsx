import {
  CreditCard02,
  Plus,
  SwitchHorizontal01,
  TrendUp02,
} from "@untitledui/icons";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import {
  type AccountDashboardSummary,
  fetchAccountDashboardSummaries,
} from "@/api/dashboard";
import {
  expenseTransactionApi,
  fetchTransactionTimeSeries,
  incomeTransactionApi,
  type TransactionTimeSeries,
} from "@/api/transactions";
import { TransactionTimeSeriesChart } from "@/components/charts/TransactionTimeSeriesChart";
import {
  DateRangeFilter,
  restoreStoredDateFilter,
  storeDateFilter,
  type TransactionDateFilterValue,
} from "@/components/DateRangeFilter";
import { PageLayout } from "@/components/PageLayout";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { LoadingIndicator } from "@/components/untitled/application/loading-indicator/loading-indicator";
import { Button } from "@/components/untitled/base/buttons/button";
import { Dropdown } from "@/components/untitled/base/dropdown/dropdown";
import { formatMoney } from "@/utils/money";

const dashboardDateFilterStorageKey = "renalo.dashboard.dateFilter";

export function TrackingPage() {
  const navigate = useNavigate();
  const [accountSummaries, setAccountSummaries] = useState<
    AccountDashboardSummary[]
  >([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(false);
  const [dateFilter, setDateFilter] = useState<TransactionDateFilterValue>(() =>
    restoreStoredDateFilter(
      window.localStorage.getItem(dashboardDateFilterStorageKey),
      new Date(),
    ),
  );
  const [expenseTimeSeries, setExpenseTimeSeries] = useState<
    TransactionTimeSeries | undefined
  >();
  const [incomeTimeSeries, setIncomeTimeSeries] = useState<
    TransactionTimeSeries | undefined
  >();
  const [expenseChartError, setExpenseChartError] = useState(false);
  const [incomeChartError, setIncomeChartError] = useState(false);
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

  useEffect(() => {
    window.localStorage.setItem(
      dashboardDateFilterStorageKey,
      storeDateFilter(dateFilter),
    );
    let isActive = true;
    setExpenseTimeSeries(undefined);
    setIncomeTimeSeries(undefined);
    setExpenseChartError(false);
    setIncomeChartError(false);

    fetchTransactionTimeSeries(expenseTransactionApi, dateFilter)
      .then((timeSeries) => {
        if (isActive) {
          setExpenseTimeSeries(timeSeries);
        }
      })
      .catch(() => {
        if (isActive) {
          setExpenseChartError(true);
        }
      });
    fetchTransactionTimeSeries(incomeTransactionApi, dateFilter)
      .then((timeSeries) => {
        if (isActive) {
          setIncomeTimeSeries(timeSeries);
        }
      })
      .catch(() => {
        if (isActive) {
          setIncomeChartError(true);
        }
      });

    return () => {
      isActive = false;
    };
  }, [dateFilter]);

  return (
    <PageLayout
      title="Dashboard"
      description="Review account balances, money flow, and trends."
      actions={<DashboardQuickAddButton onNavigate={navigate} />}
      className="dashboard-page-surface"
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
        <>
          <section
            className="dashboard-account-grid"
            aria-label="Account balances"
          >
            {accountSummaries.map((summary) => (
              <AccountSummaryCard key={summary.accountId} summary={summary} />
            ))}
          </section>
          <div className="dashboard-date-filter">
            <DateRangeFilter value={dateFilter} onChange={setDateFilter} />
          </div>
          <div className="dashboard-chart-grid">
            <TransactionTimeSeriesChart
              title="Expenses"
              tone="expense"
              timeSeries={expenseTimeSeries}
              error={
                expenseChartError
                  ? "Expense chart could not be loaded. Try again in a moment."
                  : undefined
              }
              showTrendLine
            />
            <TransactionTimeSeriesChart
              title="Income"
              tone="income"
              timeSeries={incomeTimeSeries}
              error={
                incomeChartError
                  ? "Income chart could not be loaded. Try again in a moment."
                  : undefined
              }
              showTrendLine
            />
          </div>
        </>
      )}
    </PageLayout>
  );
}

function DashboardQuickAddButton({
  onNavigate,
}: {
  onNavigate: (path: string) => void;
}) {
  return (
    <Dropdown.Root>
      <Button
        color="primary"
        size="sm"
        iconLeading={Plus}
        className="dashboard-quick-add-trigger"
      >
        Record new
      </Button>
      <Dropdown.Popover placement="bottom right" className="w-48">
        <Dropdown.Menu selectionMode="none" aria-label="Quick add">
          <Dropdown.Item
            label="Expense"
            icon={CreditCard02}
            selectionIndicator="none"
            onAction={() => onNavigate("/expenses/create")}
          />
          <Dropdown.Item
            label="Income"
            icon={TrendUp02}
            selectionIndicator="none"
            onAction={() => onNavigate("/incomes/create")}
          />
          <Dropdown.Item
            label="Transfer"
            icon={SwitchHorizontal01}
            selectionIndicator="none"
            onAction={() => onNavigate("/transfers/create")}
          />
        </Dropdown.Menu>
      </Dropdown.Popover>
    </Dropdown.Root>
  );
}

function AccountSummaryCard({ summary }: { summary: AccountDashboardSummary }) {
  const currentMonthName = new Intl.DateTimeFormat(undefined, {
    month: "long",
  }).format(new Date());

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
