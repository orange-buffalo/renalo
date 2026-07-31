import { CalendarDate, getLocalTimeZone, today } from "@internationalized/date";
import { ChevronLeft, ChevronRight } from "@untitledui/icons";
import { useState } from "react";
import {
  Dialog as AriaDialog,
  DialogTrigger as AriaDialogTrigger,
  Modal as AriaModal,
  ModalOverlay as AriaModalOverlay,
  Popover as AriaPopover,
} from "react-aria-components";
import { RangeCalendar } from "@/components/untitled/application/date-picker/range-calendar";
import { Button } from "@/components/untitled/base/buttons/button";
import { useBreakpoint } from "@/hooks/use-breakpoint";
import { cx } from "@/utils/cx";

export type DateFilterPreset =
  | "THIS_MONTH"
  | "PREVIOUS_MONTH"
  | "NEXT_MONTH"
  | "LAST_12_MONTHS"
  | "LAST_2_YEARS"
  | "LAST_3_YEARS"
  | "LAST_5_YEARS"
  | "THIS_YEAR"
  | "ALL_TIME";

export type TransactionDateFilterValue = {
  from: string | null;
  to: string | null;
  label: string;
  preset?: DateFilterPreset;
};

type CalendarRange = {
  start: CalendarDate;
  end: CalendarDate;
};

type DateRangeFilterProps = {
  value: TransactionDateFilterValue;
  onChange: (value: TransactionDateFilterValue) => void;
  maxValue?: CalendarDate;
  presets?: DateFilterPreset[];
  warnForLargeRange?: boolean;
};

const presetLabels: Record<DateFilterPreset, string> = {
  THIS_MONTH: "This month",
  PREVIOUS_MONTH: "Previous month",
  NEXT_MONTH: "Next month",
  LAST_12_MONTHS: "Last 12 months",
  LAST_2_YEARS: "Last 2 years",
  LAST_3_YEARS: "Last 3 years",
  LAST_5_YEARS: "Last 5 years",
  THIS_YEAR: "This year",
  ALL_TIME: "All time",
};

const overviewPresets: DateFilterPreset[] = [
  "THIS_MONTH",
  "PREVIOUS_MONTH",
  "NEXT_MONTH",
  "LAST_12_MONTHS",
  "THIS_YEAR",
  "ALL_TIME",
];

export const dashboardDateFilterPresets: DateFilterPreset[] = [
  "THIS_MONTH",
  "PREVIOUS_MONTH",
  "LAST_12_MONTHS",
  "LAST_2_YEARS",
  "LAST_3_YEARS",
  "LAST_5_YEARS",
  "THIS_YEAR",
  "ALL_TIME",
];

const allPresets = [
  ...new Set([...overviewPresets, ...dashboardDateFilterPresets]),
];

export function DateRangeFilter({
  value,
  onChange,
  maxValue,
  presets = overviewPresets,
  warnForLargeRange = false,
}: DateRangeFilterProps) {
  const isDesktop = useBreakpoint("md");
  const [isOpen, setIsOpen] = useState(false);
  const [draftRange, setDraftRange] = useState<CalendarRange>(() =>
    calendarRangeFromFilter(value, maxValue),
  );
  const [draftPreset, setDraftPreset] = useState<DateFilterPreset | undefined>(
    value.preset,
  );
  const [focusedValue, setFocusedValue] = useState<CalendarDate | null>(
    draftRange.start,
  );
  const canNavigateByMonth = isSingleFullMonthFilter(value);
  const canNavigateToNextMonth =
    canNavigateByMonth &&
    (!maxValue ||
      monthNavigationBase(value)
        .add({ months: 1 })
        .set({ day: 1 })
        .compare(maxValue) <= 0);

  function openChanged(open: boolean) {
    setIsOpen(open);
    if (open) {
      const nextDraftRange = calendarRangeFromFilter(value, maxValue);
      setDraftRange(nextDraftRange);
      setDraftPreset(value.preset);
      setFocusedValue(nextDraftRange.start);
    }
  }

  function applyPreset(preset: DateFilterPreset) {
    setDraftPreset(preset);
    if (preset === "ALL_TIME") {
      return;
    }
    const nextRange = constrainRangeToMax(
      calendarRangeForPreset(preset, new Date()),
      maxValue,
    );
    setDraftRange(nextRange);
    setFocusedValue(nextRange.start);
  }

  function applyDraft() {
    if (draftPreset === "ALL_TIME") {
      onChange({
        from: null,
        to: null,
        label: presetLabels.ALL_TIME,
        preset: "ALL_TIME",
      });
    } else if (draftPreset) {
      onChange(filterForPreset(draftPreset, new Date()));
    } else {
      onChange(filterForCalendarRange(draftRange));
    }
    setIsOpen(false);
  }

  const rangeDialog = (
    <AriaDialog aria-label="Date range filter" className="date-filter-dialog">
      <header className="date-filter-mobile-header">
        <h2>Choose date range</h2>
      </header>
      <div className="date-filter-presets">
        {presets.map((preset) => (
          <button
            key={preset}
            type="button"
            className={cx(
              "date-filter-preset",
              draftPreset === preset && "date-filter-preset-selected",
            )}
            disabled={isPresetAfterMax(preset, maxValue)}
            onClick={() => applyPreset(preset)}
          >
            {presetLabels[preset]}
          </button>
        ))}
      </div>
      <div className="date-filter-calendar-panel">
        <RangeCalendar
          isCompact
          value={draftRange}
          focusedValue={focusedValue}
          onFocusChange={(date) => setFocusedValue(date as CalendarDate | null)}
          onChange={(range) => {
            setDraftPreset(undefined);
            setDraftRange({
              start: range.start as CalendarDate,
              end: range.end as CalendarDate,
            });
          }}
          highlightedDates={[today(getLocalTimeZone())]}
          maxValue={maxValue}
        />
        <div className="date-filter-dialog-footer">
          <div className="date-filter-dialog-summary">
            <div className="date-filter-selected-range">
              {draftPreset === "ALL_TIME"
                ? "All time"
                : `${formatFullDate(calendarDateToIsoDate(draftRange.start))} - ${formatFullDate(calendarDateToIsoDate(draftRange.end))}`}
            </div>
            {warnForLargeRange && isLargeRange(draftPreset, draftRange) && (
              <p className="date-filter-large-range-warning" role="alert">
                This large period might load too much data and make the page
                unresponsive.
              </p>
            )}
          </div>
          <div className="date-filter-dialog-actions">
            <Button
              color="secondary"
              size="sm"
              onPress={() => setIsOpen(false)}
            >
              Cancel
            </Button>
            <Button color="primary" size="sm" onPress={applyDraft}>
              Apply
            </Button>
          </div>
        </div>
      </div>
    </AriaDialog>
  );

  return (
    <section className="date-filter-panel" aria-label="Transaction filters">
      <div className="date-filter-control">
        <Button
          aria-label="Previous date range"
          color="tertiary"
          size="sm"
          iconLeading={ChevronLeft}
          isDisabled={!canNavigateByMonth}
          onPress={() => onChange(previousMonthFilter(value))}
        />
        <AriaDialogTrigger isOpen={isOpen} onOpenChange={openChanged}>
          <Button
            color="tertiary"
            size="sm"
            className="date-filter-label-button"
          >
            {value.label}
          </Button>
          {isDesktop ? (
            <AriaPopover
              placement="bottom left"
              offset={8}
              className="date-filter-popover"
            >
              {rangeDialog}
            </AriaPopover>
          ) : (
            <AriaModalOverlay
              isDismissable
              className="date-filter-mobile-overlay"
            >
              <AriaModal className="date-filter-mobile-modal">
                {rangeDialog}
              </AriaModal>
            </AriaModalOverlay>
          )}
        </AriaDialogTrigger>
        <Button
          aria-label="Next date range"
          color="tertiary"
          size="sm"
          iconLeading={ChevronRight}
          isDisabled={!canNavigateToNextMonth}
          onPress={() => onChange(nextMonthFilter(value))}
        />
      </div>
    </section>
  );
}

export function createDefaultTransactionDateFilter(now: Date) {
  return filterForPreset("THIS_MONTH", now);
}

export function restoreStoredDateFilter(
  storedValue: string | null,
  now: Date,
  defaultPreset: DateFilterPreset = "THIS_MONTH",
): TransactionDateFilterValue {
  if (!storedValue) {
    return filterForPreset(defaultPreset, now);
  }

  try {
    const storedFilter = JSON.parse(storedValue) as Record<string, unknown>;
    if (
      typeof storedFilter.preset === "string" &&
      allPresets.includes(storedFilter.preset as DateFilterPreset)
    ) {
      return filterForPreset(storedFilter.preset as DateFilterPreset, now);
    }
    if (
      typeof storedFilter.from === "string" &&
      typeof storedFilter.to === "string" &&
      isIsoDate(storedFilter.from) &&
      isIsoDate(storedFilter.to) &&
      storedFilter.from <= storedFilter.to
    ) {
      return filterForCalendarRange({
        start: isoDateToCalendarDate(storedFilter.from),
        end: isoDateToCalendarDate(storedFilter.to),
      });
    }
  } catch {
    // Invalid browser storage falls back to the normal default.
  }

  return filterForPreset(defaultPreset, now);
}

export function storeDateFilter(value: TransactionDateFilterValue) {
  return JSON.stringify(
    value.preset
      ? { preset: value.preset }
      : { from: value.from, to: value.to },
  );
}

export function filterForPreset(
  preset: DateFilterPreset,
  now: Date,
): TransactionDateFilterValue {
  if (preset === "ALL_TIME") {
    return { from: null, to: null, label: presetLabels.ALL_TIME, preset };
  }

  const range = calendarRangeForPreset(preset, now);
  return {
    from: calendarDateToIsoDate(range.start),
    to: calendarDateToIsoDate(range.end),
    label:
      preset === "THIS_YEAR" ||
      preset === "LAST_12_MONTHS" ||
      preset === "LAST_2_YEARS" ||
      preset === "LAST_3_YEARS" ||
      preset === "LAST_5_YEARS"
        ? presetLabels[preset]
        : smartRangeLabel(range.start, range.end),
    preset,
  };
}

export function filterForCalendarRange(
  range: CalendarRange,
): TransactionDateFilterValue {
  return {
    from: calendarDateToIsoDate(range.start),
    to: calendarDateToIsoDate(range.end),
    label: smartRangeLabel(range.start, range.end),
  };
}

function previousMonthFilter(value: TransactionDateFilterValue) {
  const baseMonth = monthNavigationBase(value);
  return filterForMonth(baseMonth.subtract({ months: 1 }));
}

function nextMonthFilter(value: TransactionDateFilterValue) {
  const baseMonth = monthNavigationBase(value);
  return filterForMonth(baseMonth.add({ months: 1 }));
}

function filterForMonth(monthDate: CalendarDate): TransactionDateFilterValue {
  const start = monthDate.set({ day: 1 });
  const end = endOfMonth(start);
  return {
    from: calendarDateToIsoDate(start),
    to: calendarDateToIsoDate(end),
    label: formatMonthYear(calendarDateToIsoDate(start)),
  };
}

function calendarRangeForPreset(
  preset: Exclude<DateFilterPreset, "ALL_TIME">,
  now: Date,
): CalendarRange {
  const currentDate = dateToCalendarDate(now);
  if (preset === "THIS_MONTH") {
    const start = currentDate.set({ day: 1 });
    return { start, end: endOfMonth(start) };
  }
  if (preset === "PREVIOUS_MONTH") {
    const start = currentDate.set({ day: 1 }).subtract({ months: 1 });
    return { start, end: endOfMonth(start) };
  }
  if (preset === "NEXT_MONTH") {
    const start = currentDate.set({ day: 1 }).add({ months: 1 });
    return { start, end: endOfMonth(start) };
  }
  if (preset === "LAST_12_MONTHS") {
    return {
      start: currentDate.subtract({ months: 12 }).add({ days: 1 }),
      end: currentDate,
    };
  }
  if (preset === "LAST_2_YEARS") {
    return rollingYearRange(currentDate, 2);
  }
  if (preset === "LAST_3_YEARS") {
    return rollingYearRange(currentDate, 3);
  }
  if (preset === "LAST_5_YEARS") {
    return rollingYearRange(currentDate, 5);
  }

  const start = currentDate.set({ month: 1, day: 1 });
  return { start, end: currentDate.set({ month: 12, day: 31 }) };
}

function rollingYearRange(currentDate: CalendarDate, years: number) {
  return {
    start: currentDate.subtract({ years }).add({ days: 1 }),
    end: currentDate,
  };
}

function calendarRangeFromFilter(
  value: TransactionDateFilterValue,
  maxValue?: CalendarDate,
) {
  if (value.from && value.to) {
    return constrainRangeToMax(
      {
        start: isoDateToCalendarDate(value.from),
        end: isoDateToCalendarDate(value.to),
      },
      maxValue,
    );
  }

  return constrainRangeToMax(
    calendarRangeForPreset("THIS_MONTH", new Date()),
    maxValue,
  );
}

function constrainRangeToMax(range: CalendarRange, maxValue?: CalendarDate) {
  if (!maxValue || range.end.compare(maxValue) <= 0) {
    return range;
  }
  return {
    start: range.start.compare(maxValue) <= 0 ? range.start : maxValue,
    end: maxValue,
  };
}

function isPresetAfterMax(preset: DateFilterPreset, maxValue?: CalendarDate) {
  if (!maxValue || preset === "ALL_TIME") {
    return false;
  }
  return calendarRangeForPreset(preset, new Date()).start.compare(maxValue) > 0;
}

function monthNavigationBase(value: TransactionDateFilterValue) {
  return value.from
    ? isoDateToCalendarDate(value.from)
    : dateToCalendarDate(new Date());
}

function isSingleFullMonthFilter(value: TransactionDateFilterValue) {
  if (!value.from || !value.to) {
    return false;
  }

  const start = isoDateToCalendarDate(value.from);
  const end = isoDateToCalendarDate(value.to);
  return (
    start.year === end.year &&
    start.month === end.month &&
    start.day === 1 &&
    end.day === endOfMonth(end).day
  );
}

function isLargeRange(
  preset: DateFilterPreset | undefined,
  range: CalendarRange,
) {
  return (
    preset === "ALL_TIME" ||
    range.end.compare(range.start.add({ years: 1 })) >= 0
  );
}

function smartRangeLabel(start: CalendarDate, end: CalendarDate) {
  const startIso = calendarDateToIsoDate(start);
  const endIso = calendarDateToIsoDate(end);
  const startIsFirstDay = start.day === 1;
  const endIsLastDay = end.day === endOfMonth(end).day;

  if (startIsFirstDay && endIsLastDay) {
    if (start.year === end.year && start.month === end.month) {
      return formatMonthYear(startIso);
    }
    return `${formatMonthYear(startIso)} - ${formatMonthYear(endIso)}`;
  }

  return `${formatFullDate(startIso)} - ${formatFullDate(endIso)}`;
}

function endOfMonth(date: CalendarDate) {
  return date.set({ day: new Date(date.year, date.month, 0).getDate() });
}

function dateToCalendarDate(date: Date) {
  return new CalendarDate(
    date.getFullYear(),
    date.getMonth() + 1,
    date.getDate(),
  );
}

function isoDateToCalendarDate(isoDate: string) {
  const [year, month, day] = isoDate.split("-").map(Number);
  return new CalendarDate(year, month, day);
}

function calendarDateToIsoDate(date: CalendarDate) {
  return `${date.year}-${String(date.month).padStart(2, "0")}-${String(date.day).padStart(2, "0")}`;
}

function formatMonthYear(isoDate: string) {
  return new Intl.DateTimeFormat("en-GB", {
    month: "long",
    year: "numeric",
  }).format(parseIsoDate(isoDate));
}

function formatFullDate(isoDate: string) {
  return new Intl.DateTimeFormat("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
  }).format(parseIsoDate(isoDate));
}

function parseIsoDate(isoDate: string) {
  const [year, month, day] = isoDate.split("-").map(Number);
  return new Date(year, month - 1, day);
}

function isIsoDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return false;
  }
  const parsed = isoDateToCalendarDate(value);
  return calendarDateToIsoDate(parsed) === value;
}
