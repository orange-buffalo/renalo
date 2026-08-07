import { Maximize01, XClose } from "@untitledui/icons";
import type { CSSProperties } from "react";
import { useId, useState } from "react";
import type { TooltipContentProps } from "recharts";
import {
  Area,
  AreaChart,
  Bar,
  BarChart,
  CartesianGrid,
  Cell,
  Legend,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Scatter,
  ScatterChart,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { AiChatChart as AiChatChartData } from "@/api/aiChat";
import { chartSeriesColors } from "@/components/charts/chartPalette";
import { selectEvenlySpacedItems } from "@/components/untitled/application/charts/charts-base";
import {
  Dialog,
  Modal,
  ModalOverlay,
} from "@/components/untitled/application/modals/modal";
import { Button } from "@/components/untitled/base/buttons/button";
import { useBreakpoint } from "@/hooks/use-breakpoint";
import { cx } from "@/utils/cx";
import { formatMoneyFromMinorUnits } from "@/utils/money";

export function AiChatChart({ chart }: { chart: AiChatChartData }) {
  const titleId = useId();
  const modalTitleId = useId();
  const [isMaximized, setIsMaximized] = useState(false);

  function renderChartPanel(maximized: boolean) {
    const panelTitleId = maximized ? modalTitleId : titleId;
    return (
      <section
        className={cx("ai-chat-chart", maximized && "ai-chat-chart--maximized")}
        aria-labelledby={panelTitleId}
        data-chart-kind={chart.kind}
        data-testid="ai-chat-chart"
      >
        <header className="ai-chat-chart-header">
          <div className="ai-chat-chart-heading">
            <h3 id={panelTitleId}>{chart.title}</h3>
            <p>
              {chart.yAxis.label}
              {chart.yAxis.currency ? ` in ${chart.yAxis.currency}` : ""}
            </p>
          </div>
          <Button
            aria-label={`${maximized ? "Close" : "Maximize"} ${chart.title} chart`}
            color="tertiary"
            size="sm"
            iconLeading={maximized ? XClose : Maximize01}
            onPress={() => setIsMaximized(!maximized)}
          />
        </header>
        {chart.kind === "PIE" || chart.kind === "DONUT" ? (
          <SliceChartView chart={chart} maximized={maximized} />
        ) : chart.kind === "SCATTER" ? (
          <ScatterChartView chart={chart} />
        ) : (
          <CartesianChartView chart={chart} maximized={maximized} />
        )}
        <ChartDataTable chart={chart} />
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
            aria-label={`${chart.title} chart`}
            className="transaction-chart-modal-dialog"
          >
            {renderChartPanel(true)}
          </Dialog>
        </Modal>
      </ModalOverlay>
    </>
  );
}

function CartesianChartView({
  chart,
  maximized,
}: {
  chart: AiChatChartData;
  maximized: boolean;
}) {
  const isDesktop = useBreakpoint("md");
  const data = cartesianData(chart);
  const isHorizontal =
    chart.kind === "BAR" && chart.orientation === "HORIZONTAL";
  const axisTicks = selectEvenlySpacedItems(data, isDesktop ? 6 : 4).map(
    (row) => row.x,
  );
  const categoryAxisWidth = Math.min(
    180,
    Math.max(90, ...data.map((row) => formatXValue(row.x, chart).length * 7)),
  );
  const canvasHeight = isHorizontal
    ? Math.min(620, Math.max(230, data.length * 32 + 52))
    : 230;
  const canvasStyle = maximized
    ? undefined
    : ({ "--ai-chat-chart-height": `${canvasHeight}px` } as CSSProperties);
  const chartMargin = {
    top: isDesktop ? 8 : 16,
    right: 20,
    bottom: 0,
    left: isDesktop ? 4 : 8,
  };
  const common = (
    <>
      <CartesianGrid
        vertical={isHorizontal}
        horizontal={!isHorizontal}
        stroke="var(--border-color-secondary)"
      />
      {isHorizontal ? (
        <>
          <XAxis type="number" hide />
          <YAxis
            dataKey="x"
            type="category"
            tickLine={false}
            axisLine={false}
            width={categoryAxisWidth}
            tick={{ fill: "#626872", fontSize: 11 }}
            tickFormatter={(value) => formatXValue(String(value), chart)}
          />
        </>
      ) : (
        <>
          <XAxis
            dataKey="x"
            tickLine={false}
            axisLine={false}
            ticks={axisTicks}
            tick={{ fill: "#626872", fontSize: 11 }}
            padding={{ left: 12, right: 12 }}
            minTickGap={24}
            tickFormatter={(value) => formatXValue(String(value), chart)}
          />
          <YAxis
            axisLine={false}
            tickLine={false}
            tick={{ fill: "#626872", fontSize: 11 }}
            tickFormatter={(value) => formatAxisValue(value, chart)}
            width={isDesktop ? 82 : 64}
            domain={["auto", "auto"]}
          />
        </>
      )}
      <Tooltip
        cursor={{ stroke: "#d1d7e0", strokeWidth: 1 }}
        wrapperStyle={{ zIndex: 10, pointerEvents: "none" }}
        content={<ChartTooltip chart={chart} />}
      />
      {chart.series.length > 1 && (
        <Legend
          formatter={(value) => (
            <span className="ai-chat-chart-legend-label">{value}</span>
          )}
        />
      )}
    </>
  );
  const series = chart.series.map((item, index) => {
    const color = chartSeriesColors[index % chartSeriesColors.length];
    const dataKey = `series-${index}`;
    if (chart.kind === "AREA") {
      return (
        <Area
          key={item.name}
          type="monotone"
          dataKey={dataKey}
          name={item.name}
          stroke={color}
          fill={color}
          fillOpacity={0.18}
          strokeWidth={2.5}
          stackId={chart.stacked ? "chart" : undefined}
          isAnimationActive={false}
        />
      );
    }
    if (chart.kind === "BAR") {
      return (
        <Bar
          key={item.name}
          dataKey={dataKey}
          name={item.name}
          fill={color}
          radius={chart.stacked ? 0 : 3}
          stackId={chart.stacked ? "chart" : undefined}
          isAnimationActive={false}
        />
      );
    }
    return (
      <Line
        key={item.name}
        type="monotone"
        dataKey={dataKey}
        name={item.name}
        stroke={color}
        strokeWidth={2.5}
        dot={{ r: 2.5, fill: color }}
        activeDot={{ r: 5 }}
        isAnimationActive={false}
      />
    );
  });

  return (
    <div
      className={cx(
        "ai-chat-chart-canvas",
        maximized && "ai-chat-chart-canvas--maximized",
      )}
      style={canvasStyle}
      data-chart-tick-count={data.length}
      data-testid={`ai-chat-${chart.kind.toLowerCase()}-chart`}
    >
      <ResponsiveContainer width="100%" height="100%">
        {chart.kind === "AREA" ? (
          <AreaChart data={data} margin={chartMargin} accessibilityLayer>
            {common}
            {series}
          </AreaChart>
        ) : chart.kind === "BAR" ? (
          <BarChart
            data={data}
            layout={isHorizontal ? "vertical" : "horizontal"}
            margin={{
              ...chartMargin,
              left: isHorizontal ? 8 : chartMargin.left,
            }}
            accessibilityLayer
          >
            {common}
            {series}
          </BarChart>
        ) : (
          <LineChart data={data} margin={chartMargin} accessibilityLayer>
            {common}
            {series}
          </LineChart>
        )}
      </ResponsiveContainer>
    </div>
  );
}

function ScatterChartView({ chart }: { chart: AiChatChartData }) {
  const isDesktop = useBreakpoint("md");
  return (
    <div className="ai-chat-chart-canvas" data-testid="ai-chat-scatter-chart">
      <ResponsiveContainer width="100%" height="100%">
        <ScatterChart
          margin={{ top: 8, right: 20, bottom: 0, left: isDesktop ? 4 : 8 }}
          accessibilityLayer
        >
          <CartesianGrid stroke="var(--border-color-secondary)" />
          <XAxis
            type="number"
            dataKey="xValue"
            name={chart.xAxis.label}
            axisLine={false}
            tickLine={false}
            tick={{ fill: "#626872", fontSize: 11 }}
          />
          <YAxis
            type="number"
            dataKey="yValue"
            name={chart.yAxis.label}
            axisLine={false}
            tickLine={false}
            tick={{ fill: "#626872", fontSize: 11 }}
            tickFormatter={(value) => formatAxisValue(value, chart)}
            width={isDesktop ? 82 : 64}
          />
          <Tooltip
            cursor={{ stroke: "#d1d7e0", strokeWidth: 1 }}
            wrapperStyle={{ zIndex: 10, pointerEvents: "none" }}
            content={<ChartTooltip chart={chart} />}
          />
          {chart.series.length > 1 && (
            <Legend
              formatter={(value) => (
                <span className="ai-chat-chart-legend-label">{value}</span>
              )}
            />
          )}
          {chart.series.map((series, index) => (
            <Scatter
              key={series.name}
              name={series.name}
              fill={chartSeriesColors[index % chartSeriesColors.length]}
              data={series.points.map((point) => ({
                xValue: Number(point.x),
                yValue: Number(point.y),
                x: point.x,
                exactValue: point.y,
                series: series.name,
              }))}
              isAnimationActive={false}
            />
          ))}
        </ScatterChart>
      </ResponsiveContainer>
    </div>
  );
}

function SliceChartView({
  chart,
  maximized,
}: {
  chart: AiChatChartData;
  maximized: boolean;
}) {
  const points = chart.series[0]?.points ?? [];
  const segments = points.map((point) => ({
    ...point,
    value: Number(point.y),
    exactValue: point.y,
  }));
  const moneyTotal =
    chart.yAxis.type === "MONEY_MINOR"
      ? points.reduce((sum, point) => sum + BigInt(point.y), 0n).toString()
      : undefined;
  return (
    <div
      className={cx(
        "ai-chat-slice-chart",
        maximized && "ai-chat-slice-chart--maximized",
      )}
    >
      <div
        className={cx(
          "ai-chat-chart-canvas ai-chat-chart-canvas--slice",
          maximized && "ai-chat-chart-canvas--maximized",
        )}
        data-testid={`ai-chat-${chart.kind.toLowerCase()}-chart`}
      >
        <ResponsiveContainer width="100%" height="100%">
          <PieChart accessibilityLayer>
            <Pie
              data={segments}
              dataKey="value"
              nameKey="x"
              innerRadius={chart.kind === "DONUT" ? "58%" : 0}
              outerRadius="91%"
              paddingAngle={1.5}
              stroke="#fff"
              strokeWidth={2}
              isAnimationActive={false}
            >
              {segments.map((segment, index) => (
                <Cell
                  key={segment.x}
                  fill={chartSeriesColors[index % chartSeriesColors.length]}
                />
              ))}
            </Pie>
            <Tooltip
              wrapperStyle={{ zIndex: 10, pointerEvents: "none" }}
              content={<ChartTooltip chart={chart} />}
            />
          </PieChart>
        </ResponsiveContainer>
        {chart.kind === "DONUT" && moneyTotal && (
          <strong className="ai-chat-chart-total">
            {formatChartValue(moneyTotal, chart)}
          </strong>
        )}
      </div>
      <ul className="ai-chat-chart-legend" aria-label={`${chart.title} legend`}>
        {points.map((point, index) => (
          <li className="ai-chat-chart-legend-item" key={point.x}>
            <span
              aria-hidden="true"
              style={{
                backgroundColor:
                  chartSeriesColors[index % chartSeriesColors.length],
              }}
            />
            <strong>{formatXValue(point.x, chart)}</strong>
            <small>{formatChartValue(point.y, chart)}</small>
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
  chart,
}: TooltipContentProps<number, string> & { chart: AiChatChartData }) {
  if (!active || !payload?.length) return null;
  const source = payload[0]?.payload as
    | {
        x?: string;
        exactValue?: string;
        exactValues?: Record<string, string>;
      }
    | undefined;
  const items = payload.flatMap((item) => {
    const dataKey = String(item.dataKey ?? "");
    const exactValue = source?.exactValue ?? source?.exactValues?.[dataKey];
    return exactValue
      ? [{ name: String(item.name ?? chart.yAxis.label), value: exactValue }]
      : [];
  });
  if (!source || items.length === 0) return null;
  return (
    <div className="transaction-chart-tooltip">
      <p>{formatXValue(source.x ?? String(label ?? ""), chart)}</p>
      {items.map((item) => (
        <strong key={item.name}>
          {items.length > 1 ? `${item.name}: ` : ""}
          {formatChartValue(item.value, chart)}
        </strong>
      ))}
    </div>
  );
}

function ChartDataTable({ chart }: { chart: AiChatChartData }) {
  return (
    <table className="sr-only" data-testid="ai-chat-chart-data">
      <caption>{chart.title}</caption>
      <thead>
        <tr>
          <th>Series</th>
          <th>{chart.xAxis.label}</th>
          <th>{chart.yAxis.label}</th>
        </tr>
      </thead>
      <tbody>
        {chart.series.flatMap((series) =>
          series.points.map((point) => (
            <tr key={`${series.name}-${point.x}`}>
              <td>{series.name}</td>
              <td>{point.x}</td>
              <td>{formatChartValue(point.y, chart)}</td>
            </tr>
          )),
        )}
      </tbody>
    </table>
  );
}

function cartesianData(chart: AiChatChartData) {
  const rows = new Map<
    string,
    {
      x: string;
      exactValues: Record<string, string>;
      [key: string]: string | number | Record<string, string>;
    }
  >();
  chart.series.forEach((series, seriesIndex) => {
    series.points.forEach((point) => {
      const row = rows.get(point.x) ?? { x: point.x, exactValues: {} };
      const dataKey = `series-${seriesIndex}`;
      row[dataKey] = Number(point.y);
      row.exactValues[dataKey] = point.y;
      rows.set(point.x, row);
    });
  });
  return [...rows.values()];
}

function formatChartValue(value: string, chart: AiChatChartData) {
  return chart.yAxis.type === "MONEY_MINOR"
    ? formatMoneyFromMinorUnits(value, chart.yAxis.currency ?? "USD")
    : formatDecimal(value);
}

function formatXValue(value: string, chart: AiChatChartData) {
  if (chart.xAxis.type !== "DATE") return value;
  const date = new Date(`${value}T00:00:00`);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleDateString(undefined, {
        year: "numeric",
        month: "short",
        day: "numeric",
      });
}

function formatAxisValue(value: number | string, chart: AiChatChartData) {
  const numericValue = typeof value === "number" ? Math.round(value) : value;
  return chart.yAxis.type === "MONEY_MINOR"
    ? formatMoneyFromMinorUnits(
        String(numericValue),
        chart.yAxis.currency ?? "USD",
      )
    : formatDecimal(String(value));
}

function formatDecimal(value: string) {
  const [integer, fraction] = value.split(".");
  const formattedInteger = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  return fraction ? `${formattedInteger}.${fraction}` : formattedInteger;
}
