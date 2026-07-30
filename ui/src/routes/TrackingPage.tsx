import { getLocalTimeZone, today } from "@internationalized/date";
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
  fetchNetWorthTimeSeries,
} from "@/api/dashboard";
import {
  type DashboardChartPreset,
  fetchDashboardChartPresets,
} from "@/api/dashboardChartPresets";
import {
  expenseTransactionApi,
  fetchTransactionCategoryTotals,
  fetchTransactionTimeSeries,
  incomeTransactionApi,
  type TransactionCategoryTotals,
  type TransactionTimeSeries,
} from "@/api/transactions";
import { TransactionByCategoryChart } from "@/components/charts/TransactionByCategoryChart";
import { TransactionTimeSeriesChart } from "@/components/charts/TransactionTimeSeriesChart";
import {
  DateRangeFilter,
  restoreStoredDateFilter,
  storeDateFilter,
  type TransactionDateFilterValue,
} from "@/components/DateRangeFilter";
import { DashboardChartPresetControl } from "@/components/dashboard/DashboardChartPresetControl";
import { PageLayout } from "@/components/PageLayout";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { LoadingIndicator } from "@/components/untitled/application/loading-indicator/loading-indicator";
import { Button } from "@/components/untitled/base/buttons/button";
import { Dropdown } from "@/components/untitled/base/dropdown/dropdown";
import { formatMoney } from "@/utils/money";

const dashboardDateFilterStorageKey = "renalo.dashboard.dateFilter";

export function TrackingPage() {
  const navigate = useNavigate();
  const dashboardToday = today(getLocalTimeZone());
  const dashboardTodayIso = dashboardToday.toString();
  const [accountSummaries, setAccountSummaries] = useState<
    AccountDashboardSummary[]
  >([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState(false);
  const [dateFilter, setDateFilter] = useState<TransactionDateFilterValue>(() =>
    restoreStoredDateFilter(
      window.localStorage.getItem(dashboardDateFilterStorageKey),
      new Date(),
      "LAST_12_MONTHS",
    ),
  );
  const [expenseTimeSeries, setExpenseTimeSeries] = useState<
    TransactionTimeSeries | undefined
  >();
  const [incomeTimeSeries, setIncomeTimeSeries] = useState<
    TransactionTimeSeries | undefined
  >();
  const [expenseCategoryTotals, setExpenseCategoryTotals] = useState<
    TransactionCategoryTotals | undefined
  >();
  const [incomeCategoryTotals, setIncomeCategoryTotals] = useState<
    TransactionCategoryTotals | undefined
  >();
  const [netWorthTimeSeries, setNetWorthTimeSeries] = useState<
    TransactionTimeSeries | undefined
  >();
  const [expenseChartError, setExpenseChartError] = useState(false);
  const [incomeChartError, setIncomeChartError] = useState(false);
  const [netWorthChartError, setNetWorthChartError] = useState(false);
  const [expenseCategoryChartError, setExpenseCategoryChartError] =
    useState(false);
  const [incomeCategoryChartError, setIncomeCategoryChartError] =
    useState(false);
  const [isExpenseChartLoading, setIsExpenseChartLoading] = useState(true);
  const [isIncomeChartLoading, setIsIncomeChartLoading] = useState(true);
  const [isNetWorthChartLoading, setIsNetWorthChartLoading] = useState(true);
  const [isExpenseCategoryChartLoading, setIsExpenseCategoryChartLoading] =
    useState(true);
  const [isIncomeCategoryChartLoading, setIsIncomeCategoryChartLoading] =
    useState(true);
  const [chartPresets, setChartPresets] = useState<
    DashboardChartPreset[] | undefined
  >();
  const [chartPresetsError, setChartPresetsError] = useState(false);
  useEffect(() => {
    let isActive = true;
    setIsExpenseCategoryChartLoading(true);
    setExpenseCategoryChartError(false);

    fetchTransactionCategoryTotals(
      expenseTransactionApi,
      toAnalyticsDateFilter(dateFilter, dashboardTodayIso),
    )
      .then((totals) => {
        if (isActive) {
          setExpenseCategoryTotals(totals);
        }
      })
      .catch(() => {
        if (isActive) {
          setExpenseCategoryChartError(true);
        }
      })
      .finally(() => {
        if (isActive) {
          setIsExpenseCategoryChartLoading(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, [dashboardTodayIso, dateFilter]);

  useEffect(() => {
    let isActive = true;
    setIsIncomeCategoryChartLoading(true);
    setIncomeCategoryChartError(false);

    fetchTransactionCategoryTotals(
      incomeTransactionApi,
      toAnalyticsDateFilter(dateFilter, dashboardTodayIso),
    )
      .then((totals) => {
        if (isActive) {
          setIncomeCategoryTotals(totals);
        }
      })
      .catch(() => {
        if (isActive) {
          setIncomeCategoryChartError(true);
        }
      })
      .finally(() => {
        if (isActive) {
          setIsIncomeCategoryChartLoading(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, [dashboardTodayIso, dateFilter]);

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

    fetchDashboardChartPresets()
      .then(({ presets }) => {
        if (isActive) {
          setChartPresets(presets);
          setChartPresetsError(false);
        }
      })
      .catch(() => {
        if (isActive) {
          setChartPresetsError(true);
          setIsExpenseChartLoading(false);
          setIsIncomeChartLoading(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, []);

  const expensePresets =
    chartPresets?.filter((preset) => preset.transactionType === "EXPENSE") ??
    [];
  const incomePresets =
    chartPresets?.filter((preset) => preset.transactionType === "INCOME") ?? [];
  const activeExpensePreset = expensePresets.find((preset) => preset.isActive);
  const activeIncomePreset = incomePresets.find((preset) => preset.isActive);
  const chartPresetsLoaded = chartPresets !== undefined;

  useEffect(() => {
    window.localStorage.setItem(
      dashboardDateFilterStorageKey,
      storeDateFilter(dateFilter),
    );
  }, [dateFilter]);

  useEffect(() => {
    if (!chartPresetsLoaded) {
      return;
    }
    let isActive = true;
    setIsExpenseChartLoading(true);
    setExpenseChartError(false);

    fetchTransactionTimeSeries(
      expenseTransactionApi,
      toAnalyticsDateFilter(dateFilter, dashboardTodayIso),
      toAnalyticsFilters(activeExpensePreset),
      activeExpensePreset?.granularity,
    )
      .then((timeSeries) => {
        if (isActive) {
          setExpenseTimeSeries(timeSeries);
        }
      })
      .catch(() => {
        if (isActive) {
          setExpenseChartError(true);
        }
      })
      .finally(() => {
        if (isActive) {
          setIsExpenseChartLoading(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, [activeExpensePreset, chartPresetsLoaded, dashboardTodayIso, dateFilter]);

  useEffect(() => {
    if (!chartPresetsLoaded) {
      return;
    }
    let isActive = true;
    setIsIncomeChartLoading(true);
    setIncomeChartError(false);

    fetchTransactionTimeSeries(
      incomeTransactionApi,
      toAnalyticsDateFilter(dateFilter, dashboardTodayIso),
      toAnalyticsFilters(activeIncomePreset),
      activeIncomePreset?.granularity,
    )
      .then((timeSeries) => {
        if (isActive) {
          setIncomeTimeSeries(timeSeries);
        }
      })
      .catch(() => {
        if (isActive) {
          setIncomeChartError(true);
        }
      })
      .finally(() => {
        if (isActive) {
          setIsIncomeChartLoading(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, [activeIncomePreset, chartPresetsLoaded, dashboardTodayIso, dateFilter]);

  useEffect(() => {
    let isActive = true;
    setIsNetWorthChartLoading(true);
    setNetWorthChartError(false);

    fetchNetWorthTimeSeries(
      toAnalyticsDateFilter(dateFilter, dashboardTodayIso),
    )
      .then((timeSeries) => {
        if (isActive) {
          setNetWorthTimeSeries(timeSeries);
        }
      })
      .catch(() => {
        if (isActive) {
          setNetWorthChartError(true);
        }
      })
      .finally(() => {
        if (isActive) {
          setIsNetWorthChartLoading(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, [dashboardTodayIso, dateFilter]);

  function updatePresets(
    transactionType: DashboardChartPreset["transactionType"],
    nextPresets: DashboardChartPreset[],
  ) {
    setChartPresets((current) => [
      ...(current ?? []).filter(
        (preset) => preset.transactionType !== transactionType,
      ),
      ...nextPresets,
    ]);
  }

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
            <DateRangeFilter
              value={dateFilter}
              onChange={setDateFilter}
              maxValue={dashboardToday}
            />
          </div>
          <div className="dashboard-chart-grid">
            <TransactionTimeSeriesChart
              title="Expenses"
              tone="expense"
              timeSeries={expenseTimeSeries}
              isLoading={isExpenseChartLoading}
              error={
                chartPresetsError
                  ? "Expense chart settings could not be loaded. Refresh the page to try again."
                  : expenseChartError
                    ? "Expense chart could not be loaded. Try again in a moment."
                    : undefined
              }
              showTrendLine
              viewLabel={activeExpensePreset?.name ?? "All expenses"}
              settingsControl={
                chartPresets ? (
                  <DashboardChartPresetControl
                    transactionType="EXPENSE"
                    presets={expensePresets}
                    onPresetsChange={(presets) =>
                      updatePresets("EXPENSE", presets)
                    }
                  />
                ) : undefined
              }
            />
            <TransactionByCategoryChart
              transactionType="EXPENSE"
              totals={expenseCategoryTotals}
              isLoading={isExpenseCategoryChartLoading}
              error={
                expenseCategoryChartError
                  ? "Expense categories could not be loaded. Try again in a moment."
                  : undefined
              }
            />
            <TransactionTimeSeriesChart
              title="Income"
              tone="income"
              timeSeries={incomeTimeSeries}
              isLoading={isIncomeChartLoading}
              error={
                chartPresetsError
                  ? "Income chart settings could not be loaded. Refresh the page to try again."
                  : incomeChartError
                    ? "Income chart could not be loaded. Try again in a moment."
                    : undefined
              }
              showTrendLine
              viewLabel={activeIncomePreset?.name ?? "All income"}
              settingsControl={
                chartPresets ? (
                  <DashboardChartPresetControl
                    transactionType="INCOME"
                    presets={incomePresets}
                    onPresetsChange={(presets) =>
                      updatePresets("INCOME", presets)
                    }
                  />
                ) : undefined
              }
            />
            <TransactionByCategoryChart
              transactionType="INCOME"
              totals={incomeCategoryTotals}
              isLoading={isIncomeCategoryChartLoading}
              error={
                incomeCategoryChartError
                  ? "Income categories could not be loaded. Try again in a moment."
                  : undefined
              }
            />
            <TransactionTimeSeriesChart
              title="Net Worth"
              tone="netWorth"
              timeSeries={netWorthTimeSeries}
              isLoading={isNetWorthChartLoading}
              error={
                netWorthChartError
                  ? "Net worth chart could not be loaded. Try again in a moment."
                  : undefined
              }
              showTrendLine
              valueKind="balance"
              viewLabel="All accounts"
            />
          </div>
        </>
      )}
    </PageLayout>
  );
}

function toAnalyticsFilters(preset?: DashboardChartPreset) {
  return {
    categoryIds:
      preset?.categoryFilterMode === "INCLUDE" ? preset.categoryIds : [],
    excludedCategoryIds:
      preset?.categoryFilterMode === "EXCLUDE" ? preset.categoryIds : [],
    accountIds:
      preset?.accountFilterMode === "INCLUDE" ? preset.accountIds : [],
    excludedAccountIds:
      preset?.accountFilterMode === "EXCLUDE" ? preset.accountIds : [],
    notes: "",
  };
}

function toAnalyticsDateFilter(
  dateFilter: TransactionDateFilterValue,
  dashboardTodayIso: string,
) {
  return {
    from:
      dateFilter.from && dateFilter.from <= dashboardTodayIso
        ? dateFilter.from
        : dateFilter.from
          ? dashboardTodayIso
          : null,
    to:
      dateFilter.to && dateFilter.to < dashboardTodayIso
        ? dateFilter.to
        : dashboardTodayIso,
  };
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
