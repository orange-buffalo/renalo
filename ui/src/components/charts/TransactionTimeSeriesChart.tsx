import { Maximize01, XClose } from "@untitledui/icons";
import type { ReactNode } from "react";
import { useId, useState } from "react";
import type { TooltipContentProps } from "recharts";
import {
  Area,
  AreaChart,
  CartesianGrid,
  Line,
  matchByDataKey,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { TransactionTimeSeries } from "@/api/transactions";
import {
  ChartActiveDot,
  selectEvenlySpacedItems,
} from "@/components/untitled/application/charts/charts-base";
import { LoadingIndicator } from "@/components/untitled/application/loading-indicator/loading-indicator";
import {
  Dialog,
  Modal,
  ModalOverlay,
} from "@/components/untitled/application/modals/modal";
import { Button } from "@/components/untitled/base/buttons/button";
import { useBreakpoint } from "@/hooks/use-breakpoint";
import { cx } from "@/utils/cx";
import { formatMoney, formatMoneyInput } from "@/utils/money";

type TransactionTimeSeriesChartProps = {
  title: string;
  tone: "expense" | "income";
  timeSeries?: TransactionTimeSeries;
  error?: string;
  showTrendLine?: boolean;
  viewLabel?: string;
  settingsControl?: ReactNode;
};

type ChartPoint = {
  bucket: string;
  amountMinor: number;
  trendAmountMinor?: number;
};

type CurrencySeries = {
  currency: string;
  points: ChartPoint[];
};

const toneColors = {
  expense: "#d16a56",
  income: "#3f8067",
};

const granularityLabels = {
  DAY: "Daily totals",
  WEEK: "Weekly totals",
  MONTH: "Monthly totals",
};

export function TransactionTimeSeriesChart({
  title,
  tone,
  timeSeries,
  error,
  showTrendLine = false,
  viewLabel,
  settingsControl,
}: TransactionTimeSeriesChartProps) {
  const titleId = useId();
  const modalTitleId = useId();
  const gradientId = useId().replaceAll(":", "");
  const modalGradientId = useId().replaceAll(":", "");
  const [isMaximized, setIsMaximized] = useState(false);
  const currencySeries = timeSeries ? buildCurrencySeries(timeSeries) : [];
  const granularityLabel = timeSeries
    ? granularityLabels[timeSeries.granularity]
    : undefined;
  const dataSubtitle =
    granularityLabel && currencySeries[0]
      ? `${granularityLabel} (${currencySeries[0].currency})`
      : granularityLabel;
  const subtitle = [viewLabel, dataSubtitle].filter(Boolean).join(" · ");

  function renderChartPanel(maximized: boolean) {
    const panelTitleId = maximized ? modalTitleId : titleId;
    const panelGradientId = maximized ? modalGradientId : gradientId;

    return (
      <section
        className={cx(
          "transaction-chart-panel",
          maximized && "transaction-chart-panel-maximized",
        )}
        aria-labelledby={panelTitleId}
        data-testid="transaction-time-series-chart"
      >
        <header className="transaction-chart-header">
          <div>
            <h2 id={panelTitleId}>{title}</h2>
            {subtitle && <p>{subtitle}</p>}
          </div>
          <div className="transaction-chart-header-actions">
            {!maximized && settingsControl}
            <Button
              aria-label={`${maximized ? "Close" : "Maximize"} ${title} chart`}
              color="tertiary"
              size="sm"
              iconLeading={maximized ? XClose : Maximize01}
              onPress={() => setIsMaximized(!maximized)}
            />
          </div>
        </header>

        {error ? (
          <p
            className="transaction-chart-message transaction-chart-error"
            role="alert"
          >
            {error}
          </p>
        ) : !timeSeries ? (
          <div
            className="transaction-chart-message"
            role="status"
            aria-busy="true"
            aria-label={`Loading ${title.toLowerCase()}`}
          >
            <LoadingIndicator size="sm" />
          </div>
        ) : currencySeries.length === 0 ? (
          <p className="transaction-chart-message">
            No matching transactions to chart.
          </p>
        ) : (
          <div className="transaction-chart-grid">
            {currencySeries.map((series) => (
              <CurrencyAreaChart
                key={series.currency}
                series={series}
                granularity={timeSeries.granularity}
                color={toneColors[tone]}
                gradientId={`${panelGradientId}-${series.currency}`}
                showTrendLine={showTrendLine}
              />
            ))}
          </div>
        )}

        {timeSeries && (
          <table
            className="sr-only"
            aria-label={`${title} data`}
            data-testid="transaction-chart-data"
          >
            <thead>
              <tr>
                <th>Bucket</th>
                <th>Currency</th>
                <th>Amount in minor units</th>
              </tr>
            </thead>
            <tbody>
              {timeSeries.points.map((point) => (
                <tr
                  key={`${point.bucket}-${point.currency}`}
                  data-testid="transaction-chart-point"
                  data-bucket={point.bucket}
                  data-currency={point.currency}
                  data-amount-minor={point.amountMinor}
                >
                  <td>{point.bucket}</td>
                  <td>{point.currency}</td>
                  <td>{point.amountMinor}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    );
  }

  return (
    <>
      {renderChartPanel(false)}
      <ModalOverlay
        isOpen={isMaximized}
        onOpenChange={setIsMaximized}
        isDismissable
        className="transaction-chart-modal-overlay"
      >
        <Modal className="transaction-chart-modal">
          <Dialog
            aria-label={`${title} chart`}
            className="transaction-chart-modal-dialog"
          >
            {renderChartPanel(true)}
          </Dialog>
        </Modal>
      </ModalOverlay>
    </>
  );
}

function CurrencyAreaChart({
  series,
  granularity,
  color,
  gradientId,
  showTrendLine,
}: {
  series: CurrencySeries;
  granularity: TransactionTimeSeries["granularity"];
  color: string;
  gradientId: string;
  showTrendLine: boolean;
}) {
  const isDesktop = useBreakpoint("md");
  const axisTicks = selectEvenlySpacedItems(series.points, 5).map(
    (point) => point.bucket,
  );

  return (
    <div className="transaction-currency-chart">
      <div className="transaction-chart-canvas">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart
            data={series.points}
            margin={{
              top: isDesktop ? 8 : 16,
              right: isDesktop ? 8 : 12,
              bottom: 0,
              left: isDesktop ? 4 : 12,
            }}
            accessibilityLayer
          >
            <defs>
              <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%" stopColor={color} stopOpacity={0.24} />
                <stop offset="95%" stopColor={color} stopOpacity={0.02} />
              </linearGradient>
            </defs>
            <CartesianGrid
              vertical={false}
              stroke="var(--border-color-secondary)"
            />
            <XAxis
              dataKey="bucket"
              axisLine={false}
              tickLine={false}
              ticks={axisTicks}
              tickFormatter={(bucket) => formatBucketTick(bucket, granularity)}
              tick={{ fill: "#626872", fontSize: 12 }}
              minTickGap={24}
            />
            <YAxis
              allowDecimals={false}
              axisLine={false}
              tickLine={false}
              tick={
                isDesktop ? (
                  { fill: "#626872", fontSize: 12 }
                ) : (
                  <MobileYAxisTick currency={series.currency} />
                )
              }
              tickFormatter={(amountMinor) =>
                formatMoneyInput(Number(amountMinor), series.currency)
              }
              width={isDesktop ? 72 : 1}
            />
            <Tooltip
              cursor={{ stroke: "#d1d7e0", strokeWidth: 1 }}
              wrapperStyle={{ zIndex: 10, pointerEvents: "none" }}
              content={(props) => (
                <TransactionChartTooltip
                  {...props}
                  currency={series.currency}
                  granularity={granularity}
                />
              )}
            />
            <Area
              type="monotone"
              dataKey="amountMinor"
              name={series.currency}
              stroke={color}
              strokeWidth={2}
              fill={`url(#${gradientId})`}
              activeDot={<ChartActiveDot color={color} />}
              animationMatchBy={matchByDataKey("bucket")}
              isAnimationActive={!showTrendLine}
            />
            {showTrendLine && series.points.length > 1 && (
              <Line
                type="linear"
                dataKey="trendAmountMinor"
                name="Trend"
                stroke={color}
                strokeWidth={2}
                strokeDasharray="6 5"
                strokeOpacity={0.8}
                dot={false}
                activeDot={false}
                isAnimationActive={false}
              />
            )}
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

function MobileYAxisTick({
  x = 0,
  y = 0,
  payload,
  currency,
}: {
  x?: number;
  y?: number;
  payload?: { value: number };
  currency: string;
}) {
  if (!payload) {
    return null;
  }

  return (
    <text x={x + 4} y={y - 4} fill="#626872" fontSize="8" textAnchor="start">
      {formatMoneyInput(payload.value, currency)}
    </text>
  );
}

function TransactionChartTooltip({
  active,
  payload,
  label,
  currency,
  granularity,
}: TooltipContentProps & {
  currency: string;
  granularity: TransactionTimeSeries["granularity"];
}) {
  if (!active || !payload?.length || label === undefined) {
    return null;
  }

  return (
    <div
      className="transaction-chart-tooltip"
      data-testid="transaction-chart-tooltip"
    >
      <p>{formatBucketTooltip(String(label), granularity)}</p>
      <strong>{formatMoney(Number(payload[0].value), currency)}</strong>
    </div>
  );
}

function buildCurrencySeries(timeSeries: TransactionTimeSeries) {
  if (!timeSeries.from || !timeSeries.to || timeSeries.points.length === 0) {
    return [];
  }

  const buckets = createBuckets(
    timeSeries.from,
    timeSeries.to,
    timeSeries.granularity,
  );
  const amounts = new Map(
    timeSeries.points.map((point) => [
      `${point.currency}:${point.bucket}`,
      point.amountMinor,
    ]),
  );
  const currencies = Array.from(
    new Set(timeSeries.points.map((point) => point.currency)),
  ).sort();

  return currencies.map((currency) => {
    const points = buckets.map((bucket) => ({
      bucket,
      amountMinor: amounts.get(`${currency}:${bucket}`) ?? 0,
    }));
    return {
      currency,
      points: addLinearTrend(points),
    };
  });
}

function addLinearTrend(points: ChartPoint[]) {
  if (points.length < 2) {
    return points;
  }

  const xMean = (points.length - 1) / 2;
  const yMean =
    points.reduce((total, point) => total + point.amountMinor, 0) /
    points.length;
  const slopeNumerator = points.reduce(
    (total, point, index) =>
      total + (index - xMean) * (point.amountMinor - yMean),
    0,
  );
  const slopeDenominator = points.reduce(
    (total, _, index) => total + (index - xMean) ** 2,
    0,
  );
  const slope = slopeNumerator / slopeDenominator;

  return points.map((point, index) => ({
    ...point,
    trendAmountMinor: Math.round(yMean + slope * (index - xMean)),
  }));
}

function createBuckets(
  from: string,
  to: string,
  granularity: TransactionTimeSeries["granularity"],
) {
  const lastDate = parseIsoDate(to);
  const currentDate = alignBucketStart(parseIsoDate(from), granularity);
  const buckets: string[] = [];

  while (currentDate <= lastDate) {
    buckets.push(formatIsoDate(currentDate));
    if (granularity === "MONTH") {
      currentDate.setMonth(currentDate.getMonth() + 1);
    } else {
      currentDate.setDate(
        currentDate.getDate() + (granularity === "WEEK" ? 7 : 1),
      );
    }
  }

  return buckets;
}

function alignBucketStart(
  date: Date,
  granularity: TransactionTimeSeries["granularity"],
) {
  if (granularity === "MONTH") {
    date.setDate(1);
  } else if (granularity === "WEEK") {
    const daysSinceMonday = (date.getDay() + 6) % 7;
    date.setDate(date.getDate() - daysSinceMonday);
  }
  return date;
}

function formatBucketTick(
  bucket: string,
  granularity: TransactionTimeSeries["granularity"],
) {
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    ...(granularity !== "MONTH" && { day: "numeric" }),
    ...(granularity === "MONTH" && { year: "2-digit" }),
  }).format(parseIsoDate(bucket));
}

function formatBucketTooltip(
  bucket: string,
  granularity: TransactionTimeSeries["granularity"],
) {
  const formattedDate = new Intl.DateTimeFormat(undefined, {
    day: granularity === "MONTH" ? undefined : "numeric",
    month: "long",
    year: "numeric",
  }).format(parseIsoDate(bucket));
  return granularity === "WEEK" ? `Week of ${formattedDate}` : formattedDate;
}

function parseIsoDate(isoDate: string) {
  const [year, month, day] = isoDate.split("-").map(Number);
  return new Date(year, month - 1, day);
}

function formatIsoDate(date: Date) {
  return [
    date.getFullYear(),
    String(date.getMonth() + 1).padStart(2, "0"),
    String(date.getDate()).padStart(2, "0"),
  ].join("-");
}
