import { useId } from "react";
import type { TooltipContentProps } from "recharts";
import {
  Area,
  AreaChart,
  CartesianGrid,
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
import { formatMoney, formatMoneyInput } from "@/utils/money";

type TransactionTimeSeriesChartProps = {
  title: string;
  tone: "expense" | "income";
  timeSeries?: TransactionTimeSeries;
  error?: string;
};

type ChartPoint = {
  bucket: string;
  amountMinor: number;
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
}: TransactionTimeSeriesChartProps) {
  const titleId = useId();
  const gradientId = useId().replaceAll(":", "");
  const currencySeries = timeSeries ? buildCurrencySeries(timeSeries) : [];
  const granularityLabel = timeSeries
    ? granularityLabels[timeSeries.granularity]
    : undefined;

  return (
    <section
      className="transaction-chart-panel"
      aria-labelledby={titleId}
      data-testid="transaction-time-series-chart"
    >
      <header className="transaction-chart-header">
        <div>
          <h2 id={titleId}>{title}</h2>
          {granularityLabel && <p>{granularityLabel}</p>}
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
              gradientId={`${gradientId}-${series.currency}`}
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

function CurrencyAreaChart({
  series,
  granularity,
  color,
  gradientId,
}: {
  series: CurrencySeries;
  granularity: TransactionTimeSeries["granularity"];
  color: string;
  gradientId: string;
}) {
  const axisTicks = selectEvenlySpacedItems(series.points, 5).map(
    (point) => point.bucket,
  );

  return (
    <div className="transaction-currency-chart">
      <h3>{series.currency}</h3>
      <div className="transaction-chart-canvas">
        <ResponsiveContainer width="100%" height="100%">
          <AreaChart
            data={series.points}
            margin={{ top: 8, right: 8, bottom: 0, left: 4 }}
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
              tick={{ fill: "#626872", fontSize: 12 }}
              tickFormatter={(amountMinor) =>
                formatMoneyInput(Number(amountMinor), series.currency)
              }
              width={72}
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
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>
    </div>
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

  return currencies.map((currency) => ({
    currency,
    points: buckets.map((bucket) => ({
      bucket,
      amountMinor: amounts.get(`${currency}:${bucket}`) ?? 0,
    })),
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
