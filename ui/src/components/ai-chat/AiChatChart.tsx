import { useId } from "react";
import type { TooltipContentProps } from "recharts";
import {
  CartesianGrid,
  Cell,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { AiChatChart as AiChatChartData } from "@/api/aiChat";
import { transactionCategoryColors } from "@/components/charts/TransactionByCategoryChart";
import { formatMoneyFromMinorUnits } from "@/utils/money";

export function AiChatChart({ chart }: { chart: AiChatChartData }) {
  const titleId = useId();
  return (
    <section
      className="ai-chat-chart"
      aria-labelledby={titleId}
      data-chart-kind={chart.kind}
      data-testid="ai-chat-chart"
    >
      <header className="ai-chat-chart-header">
        <h3 id={titleId}>{chart.title}</h3>
        <p>Values in {chart.currency}</p>
      </header>
      {chart.kind === "LINE" ? (
        <LineChartView chart={chart} />
      ) : (
        <SliceChartView chart={chart} />
      )}
      <ChartDataTable chart={chart} />
    </section>
  );
}

function LineChartView({ chart }: { chart: AiChatChartData }) {
  const series = chart.series[0];
  const points = (series?.points ?? []).map((point) => ({
    ...point,
    value: Number(BigInt(point.amountMinor)),
  }));
  return (
    <div className="ai-chat-chart-canvas" data-testid="ai-chat-line-chart">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart
          data={points}
          margin={{ top: 8, right: 12, bottom: 4, left: 0 }}
        >
          <CartesianGrid
            vertical={false}
            stroke="var(--border-color-secondary)"
          />
          <XAxis
            dataKey="label"
            tickLine={false}
            axisLine={false}
            minTickGap={28}
            tickFormatter={formatDateLabel}
          />
          <YAxis hide domain={["auto", "auto"]} />
          <Tooltip content={<ChartTooltip currency={chart.currency} />} />
          <Line
            type="monotone"
            dataKey="value"
            name={series?.name ?? chart.title}
            stroke="#626da5"
            strokeWidth={2.5}
            dot={{ r: 2.5, fill: "#626da5" }}
            activeDot={{ r: 5 }}
            isAnimationActive={false}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

function SliceChartView({ chart }: { chart: AiChatChartData }) {
  const segments = chart.segments.map((segment) => ({
    ...segment,
    value: Number(BigInt(segment.amountMinor)),
  }));
  const total = chart.segments.reduce(
    (sum, segment) => sum + BigInt(segment.amountMinor),
    0n,
  );
  return (
    <div className="ai-chat-slice-chart">
      <div className="ai-chat-chart-canvas ai-chat-chart-canvas--slice">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie
              data={segments}
              dataKey="value"
              nameKey="label"
              innerRadius={chart.kind === "DONUT" ? "58%" : 0}
              outerRadius="91%"
              paddingAngle={1.5}
              stroke="#fff"
              strokeWidth={2}
              isAnimationActive={false}
            >
              {segments.map((segment, index) => (
                <Cell
                  key={segment.label}
                  fill={
                    transactionCategoryColors[
                      index % transactionCategoryColors.length
                    ]
                  }
                />
              ))}
            </Pie>
            <Tooltip content={<ChartTooltip currency={chart.currency} />} />
          </PieChart>
        </ResponsiveContainer>
        {chart.kind === "DONUT" && (
          <strong className="ai-chat-chart-total">
            {formatMoneyFromMinorUnits(total.toString(), chart.currency)}
          </strong>
        )}
      </div>
      <ul className="ai-chat-chart-legend" aria-label={`${chart.title} legend`}>
        {chart.segments.map((segment, index) => (
          <li className="ai-chat-chart-legend-item" key={segment.label}>
            <span
              aria-hidden="true"
              style={{
                backgroundColor:
                  transactionCategoryColors[
                    index % transactionCategoryColors.length
                  ],
              }}
            />
            <strong>{segment.label}</strong>
            <small>
              {formatMoneyFromMinorUnits(segment.amountMinor, chart.currency)}
            </small>
          </li>
        ))}
      </ul>
    </div>
  );
}

function ChartTooltip({
  active,
  payload,
  label,
  currency,
}: TooltipContentProps<number, string> & { currency: string }) {
  const point = payload?.[0]?.payload as
    | { label?: string; amountMinor?: string }
    | undefined;
  if (!active || !point?.amountMinor) return null;
  return (
    <div className="transaction-chart-tooltip">
      <p>{point.label ?? String(label ?? "")}</p>
      <strong>{formatMoneyFromMinorUnits(point.amountMinor, currency)}</strong>
    </div>
  );
}

function ChartDataTable({ chart }: { chart: AiChatChartData }) {
  const rows =
    chart.kind === "LINE"
      ? chart.series.flatMap((series) =>
          series.points.map((point) => ({
            label: point.label,
            series: series.name,
            amountMinor: point.amountMinor,
          })),
        )
      : chart.segments.map((segment) => ({
          label: segment.label,
          series: chart.kind === "PIE" ? "Pie segment" : "Donut segment",
          amountMinor: segment.amountMinor,
        }));
  return (
    <table className="sr-only" data-testid="ai-chat-chart-data">
      <caption>{chart.title}</caption>
      <thead>
        <tr>
          <th>Series</th>
          <th>Label</th>
          <th>Value</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={`${row.series}-${row.label}`}>
            <td>{row.series}</td>
            <td>{row.label}</td>
            <td>
              {formatMoneyFromMinorUnits(row.amountMinor, chart.currency)}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function formatDateLabel(value: string) {
  const date = new Date(`${value}T00:00:00`);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleDateString(undefined, { month: "short", day: "numeric" });
}
