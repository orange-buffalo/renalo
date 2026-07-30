import { useId, useState } from "react";
import {
  Button as AriaButton,
  Dialog as AriaDialog,
  DialogTrigger as AriaDialogTrigger,
  Popover as AriaPopover,
} from "react-aria-components";
import type { TooltipContentProps } from "recharts";
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";
import type {
  TransactionApiConfig,
  TransactionCategoryTotals,
} from "@/api/transactions";
import { LoadingIndicator } from "@/components/untitled/application/loading-indicator/loading-indicator";
import { formatMoney } from "@/utils/money";

type TransactionByCategoryChartProps = {
  transactionType: TransactionApiConfig["type"];
  totals?: TransactionCategoryTotals;
  isLoading?: boolean;
  error?: string;
};

const visibleLegendCategoryCount = 8;
const dimmedOpacity = 0.15;
const chartConfigs = {
  EXPENSE: {
    title: "Expenses by category",
    subjectPlural: "expenses",
    categoryLabel: "Expense",
    testPrefix: "expense",
    storageKey: "renalo.dashboard.expenseCategoryVisibility",
  },
  INCOME: {
    title: "Income by category",
    subjectPlural: "income",
    categoryLabel: "Income",
    testPrefix: "income",
    storageKey: "renalo.dashboard.incomeCategoryVisibility",
  },
} satisfies Record<TransactionApiConfig["type"], ChartConfig>;
const colors = [
  "#3976c5",
  "#65a3df",
  "#8abce8",
  "#75a94a",
  "#9bc767",
  "#b8d98d",
  "#76529a",
  "#9b73bc",
  "#c0a5d4",
  "#d65d5d",
  "#e78787",
  "#efb0a8",
  "#e5ad35",
  "#f2c75c",
  "#f5d889",
  "#d47f38",
];

export function TransactionByCategoryChart({
  transactionType,
  totals,
  isLoading = !totals,
  error,
}: TransactionByCategoryChartProps) {
  const config = chartConfigs[transactionType];
  const titleId = useId();
  const [hoveredCategoryId, setHoveredCategoryId] = useState<number>();
  const [hiddenCategoryIds, setHiddenCategoryIds] = useState(() =>
    loadHiddenCategoryIds(config),
  );
  const categories = totals?.categories ?? [];
  const visibleCategories = categories.filter(
    (category) => !hiddenCategoryIds.has(category.categoryId),
  );
  const visibleTotal = visibleCategories.reduce(
    (sum, category) => sum + category.amountMinor,
    0,
  );
  const currency = categories[0]?.currency;
  const mainLegendCategories = categories.slice(0, visibleLegendCategoryCount);
  const overflowLegendCategories = categories.slice(visibleLegendCategoryCount);

  function setCategoryVisible(categoryId: number, isVisible: boolean) {
    setHiddenCategoryIds((current) => {
      const next = new Set(current);
      if (isVisible) {
        next.delete(categoryId);
      } else {
        next.add(categoryId);
      }
      storeHiddenCategoryIds(config, next);
      return next;
    });
  }

  return (
    <section
      className="transaction-chart-panel transaction-category-chart-panel"
      aria-labelledby={titleId}
      aria-busy={isLoading}
      data-testid={`${config.testPrefix}-category-chart`}
    >
      <header className="transaction-chart-header">
        <div>
          <h2 id={titleId}>{config.title}</h2>
          {currency && <p>Totals in {currency}</p>}
        </div>
        {isLoading && totals && (
          <span
            className="transaction-chart-refresh-indicator"
            role="status"
            aria-label={`Refreshing ${config.subjectPlural} by category`}
          >
            <LoadingIndicator size="sm" />
          </span>
        )}
      </header>

      {error ? (
        <p
          className="transaction-chart-message transaction-chart-error"
          role="alert"
        >
          {error}
        </p>
      ) : !totals ? (
        <div
          className="transaction-chart-message"
          role="status"
          aria-busy="true"
          aria-label={`Loading ${config.subjectPlural} by category`}
        >
          <LoadingIndicator size="sm" />
        </div>
      ) : categories.length === 0 ? (
        <p className="transaction-chart-message">
          No matching {config.subjectPlural} to chart.
        </p>
      ) : (
        <div className="transaction-category-chart-content">
          <div className="transaction-category-legend">
            <fieldset>
              <legend className="sr-only">
                {config.categoryLabel} categories
              </legend>
              {mainLegendCategories.map((category, index) => (
                <CategoryLegendItem
                  key={category.categoryId}
                  category={category}
                  color={colors[index % colors.length]}
                  isVisible={!hiddenCategoryIds.has(category.categoryId)}
                  visibleTotal={visibleTotal}
                  isDimmed={
                    hoveredCategoryId !== undefined &&
                    hoveredCategoryId !== category.categoryId
                  }
                  onToggle={setCategoryVisible}
                />
              ))}
            </fieldset>
            {overflowLegendCategories.length > 0 && (
              <AriaDialogTrigger>
                <AriaButton className="transaction-category-more-button">
                  {overflowLegendCategories.length} more
                </AriaButton>
                <AriaPopover
                  placement="bottom left"
                  offset={6}
                  className="transaction-category-more-popover"
                >
                  <AriaDialog
                    aria-label={`More ${config.categoryLabel.toLowerCase()} categories`}
                    className="transaction-category-more-dialog"
                  >
                    {overflowLegendCategories.map((category, index) => (
                      <CategoryLegendItem
                        key={category.categoryId}
                        category={category}
                        color={
                          colors[
                            (index + visibleLegendCategoryCount) % colors.length
                          ]
                        }
                        isVisible={!hiddenCategoryIds.has(category.categoryId)}
                        visibleTotal={visibleTotal}
                        isDimmed={
                          hoveredCategoryId !== undefined &&
                          hoveredCategoryId !== category.categoryId
                        }
                        onToggle={setCategoryVisible}
                      />
                    ))}
                  </AriaDialog>
                </AriaPopover>
              </AriaDialogTrigger>
            )}
          </div>
          {visibleCategories.length === 0 ? (
            <p className="transaction-category-all-hidden">
              Select a category to show it in the chart.
            </p>
          ) : (
            <div className="transaction-category-donut">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart accessibilityLayer>
                  <Pie
                    data={visibleCategories}
                    dataKey="amountMinor"
                    nameKey="categoryName"
                    innerRadius="61%"
                    outerRadius="94%"
                    paddingAngle={1.5}
                    stroke="#ffffff"
                    strokeWidth={2}
                    isAnimationActive={false}
                  >
                    {visibleCategories.map((category) => {
                      const originalIndex = categories.findIndex(
                        (item) => item.categoryId === category.categoryId,
                      );
                      return (
                        <Cell
                          key={category.categoryId}
                          fill={colors[originalIndex % colors.length]}
                          opacity={
                            hoveredCategoryId === undefined ||
                            hoveredCategoryId === category.categoryId
                              ? 1
                              : dimmedOpacity
                          }
                          className="transaction-category-slice"
                          onPointerEnter={(event) => {
                            if (event.pointerType === "mouse") {
                              setHoveredCategoryId(category.categoryId);
                            }
                          }}
                          onPointerLeave={(event) => {
                            if (event.pointerType === "mouse") {
                              setHoveredCategoryId(undefined);
                            }
                          }}
                        />
                      );
                    })}
                  </Pie>
                  <Tooltip
                    wrapperStyle={{ zIndex: 10, pointerEvents: "none" }}
                    content={(props) => (
                      <CategoryChartTooltip {...props} currency={currency} />
                    )}
                  />
                </PieChart>
              </ResponsiveContainer>
              <div className="transaction-category-total" aria-hidden="true">
                <strong>{formatMoney(visibleTotal, currency)}</strong>
                <span>Total</span>
              </div>
            </div>
          )}
        </div>
      )}

      {totals && (
        <table
          className="sr-only"
          aria-label={`${config.title} data`}
          data-testid={`${config.testPrefix}-category-chart-data`}
        >
          <thead>
            <tr>
              <th>Category</th>
              <th>Currency</th>
              <th>Amount in minor units</th>
              <th>Visible</th>
            </tr>
          </thead>
          <tbody>
            {categories.map((category) => (
              <tr
                key={category.categoryId}
                data-testid="transaction-category-chart-row"
                data-category-id={category.categoryId}
                data-category-name={category.categoryName}
                data-currency={category.currency}
                data-amount-minor={category.amountMinor}
                data-visible={!hiddenCategoryIds.has(category.categoryId)}
                data-highlighted={hoveredCategoryId === category.categoryId}
              >
                <td>{category.categoryName}</td>
                <td>{category.currency}</td>
                <td>{category.amountMinor}</td>
                <td>{String(!hiddenCategoryIds.has(category.categoryId))}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

type Category = TransactionCategoryTotals["categories"][number];

function CategoryLegendItem({
  category,
  color,
  isVisible,
  visibleTotal,
  isDimmed,
  onToggle,
}: {
  category: Category;
  color: string;
  isVisible: boolean;
  visibleTotal: number;
  isDimmed: boolean;
  onToggle: (categoryId: number, isVisible: boolean) => void;
}) {
  const percentage =
    visibleTotal === 0
      ? 0
      : Math.round((category.amountMinor / visibleTotal) * 100);
  return (
    <AriaButton
      aria-label={`${isVisible ? "Hide" : "Show"} ${category.categoryName}`}
      aria-pressed={isVisible}
      className="transaction-category-toggle"
      data-visible={isVisible}
      style={{ opacity: !isVisible || isDimmed ? dimmedOpacity : 1 }}
      onPress={() => onToggle(category.categoryId, !isVisible)}
    >
      <span
        className="transaction-category-color"
        style={{ backgroundColor: color }}
      />
      <span className="transaction-category-name">{category.categoryName}</span>
      {isVisible && (
        <span className="transaction-category-percentage">{percentage}%</span>
      )}
    </AriaButton>
  );
}

function CategoryChartTooltip({
  active,
  payload,
  currency,
}: TooltipContentProps & { currency?: string }) {
  if (!active || !payload?.length || !currency) {
    return null;
  }
  return (
    <div className="transaction-chart-tooltip">
      <p>{payload[0].name}</p>
      <strong>{formatMoney(Number(payload[0].value), currency)}</strong>
    </div>
  );
}

type ChartConfig = {
  title: string;
  subjectPlural: string;
  categoryLabel: string;
  testPrefix: string;
  storageKey: string;
};

function loadHiddenCategoryIds(config: ChartConfig) {
  try {
    const stored = window.localStorage.getItem(config.storageKey);
    if (!stored) {
      return new Set<number>();
    }
    const value: unknown = JSON.parse(stored);
    if (
      typeof value !== "object" ||
      value === null ||
      !("hiddenCategoryIds" in value) ||
      !Array.isArray(value.hiddenCategoryIds) ||
      !value.hiddenCategoryIds.every((id) => Number.isSafeInteger(id) && id > 0)
    ) {
      return new Set<number>();
    }
    return new Set<number>(value.hiddenCategoryIds);
  } catch (error) {
    console.warn(
      `Could not restore ${config.subjectPlural} category chart visibility`,
      error,
    );
    return new Set<number>();
  }
}

function storeHiddenCategoryIds(config: ChartConfig, categoryIds: Set<number>) {
  try {
    window.localStorage.setItem(
      config.storageKey,
      JSON.stringify({ hiddenCategoryIds: Array.from(categoryIds).sort() }),
    );
  } catch (error) {
    console.warn(
      `Could not store ${config.subjectPlural} category chart visibility`,
      error,
    );
  }
}
