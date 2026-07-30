import { useId, useState } from "react";
import {
  Button as AriaButton,
  Dialog as AriaDialog,
  DialogTrigger as AriaDialogTrigger,
  Popover as AriaPopover,
} from "react-aria-components";
import type { TooltipContentProps } from "recharts";
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";
import type { TransactionCategoryTotals } from "@/api/transactions";
import { LoadingIndicator } from "@/components/untitled/application/loading-indicator/loading-indicator";
import { formatMoney } from "@/utils/money";

type ExpenseByCategoryChartProps = {
  totals?: TransactionCategoryTotals;
  isLoading?: boolean;
  error?: string;
};

const storageKey = "renalo.dashboard.expenseCategoryVisibility";
const visibleLegendCategoryCount = 8;
const dimmedOpacity = 0.15;
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

export function ExpenseByCategoryChart({
  totals,
  isLoading = !totals,
  error,
}: ExpenseByCategoryChartProps) {
  const titleId = useId();
  const [hoveredCategoryId, setHoveredCategoryId] = useState<number>();
  const [hiddenCategoryIds, setHiddenCategoryIds] = useState(
    loadHiddenCategoryIds,
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
      storeHiddenCategoryIds(next);
      return next;
    });
  }

  return (
    <section
      className="transaction-chart-panel expense-category-chart-panel"
      aria-labelledby={titleId}
      aria-busy={isLoading}
      data-testid="expense-category-chart"
    >
      <header className="transaction-chart-header">
        <div>
          <h2 id={titleId}>Expenses by category</h2>
          {currency && <p>Totals in {currency}</p>}
        </div>
        {isLoading && totals && (
          <span
            className="transaction-chart-refresh-indicator"
            role="status"
            aria-label="Refreshing expenses by category"
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
          aria-label="Loading expenses by category"
        >
          <LoadingIndicator size="sm" />
        </div>
      ) : categories.length === 0 ? (
        <p className="transaction-chart-message">
          No matching expenses to chart.
        </p>
      ) : (
        <div className="expense-category-chart-content">
          <div className="expense-category-legend">
            <fieldset>
              <legend className="sr-only">Expense categories</legend>
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
                <AriaButton className="expense-category-more-button">
                  {overflowLegendCategories.length} more
                </AriaButton>
                <AriaPopover
                  placement="bottom left"
                  offset={6}
                  className="expense-category-more-popover"
                >
                  <AriaDialog
                    aria-label="More expense categories"
                    className="expense-category-more-dialog"
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
            <p className="expense-category-all-hidden">
              Select a category to show it in the chart.
            </p>
          ) : (
            <div className="expense-category-donut">
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
                          className="expense-category-slice"
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
              <div className="expense-category-total" aria-hidden="true">
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
          aria-label="Expenses by category data"
          data-testid="expense-category-chart-data"
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
                data-testid="expense-category-chart-row"
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
      className="expense-category-toggle"
      data-visible={isVisible}
      style={{ opacity: !isVisible || isDimmed ? dimmedOpacity : 1 }}
      onPress={() => onToggle(category.categoryId, !isVisible)}
    >
      <span
        className="expense-category-color"
        style={{ backgroundColor: color }}
      />
      <span className="expense-category-name">{category.categoryName}</span>
      {isVisible && (
        <span className="expense-category-percentage">{percentage}%</span>
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

function loadHiddenCategoryIds() {
  try {
    const stored = window.localStorage.getItem(storageKey);
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
    console.warn("Could not restore expense category chart visibility", error);
    return new Set<number>();
  }
}

function storeHiddenCategoryIds(categoryIds: Set<number>) {
  try {
    window.localStorage.setItem(
      storageKey,
      JSON.stringify({ hiddenCategoryIds: Array.from(categoryIds).sort() }),
    );
  } catch (error) {
    console.warn("Could not store expense category chart visibility", error);
  }
}
