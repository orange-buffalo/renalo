import { CalendarDate, getLocalTimeZone, today } from "@internationalized/date";
import { ChevronLeft, ChevronRight } from "@untitledui/icons";
import { useState } from "react";
import {
  Dialog as AriaDialog,
  DialogTrigger as AriaDialogTrigger,
  Popover as AriaPopover,
} from "react-aria-components";
import { RangeCalendar } from "@/components/untitled/application/date-picker/range-calendar";
import { Button } from "@/components/untitled/base/buttons/button";
import { cx } from "@/utils/cx";

export type DateFilterPreset =
  | "THIS_MONTH"
  | "PREVIOUS_MONTH"
  | "NEXT_MONTH"
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
};

const presetLabels: Record<DateFilterPreset, string> = {
  THIS_MONTH: "This month",
  PREVIOUS_MONTH: "Previous month",
  NEXT_MONTH: "Next month",
  THIS_YEAR: "This year",
  ALL_TIME: "All time",
};

const presetOrder: DateFilterPreset[] = [
  "THIS_MONTH",
  "PREVIOUS_MONTH",
  "NEXT_MONTH",
  "THIS_YEAR",
  "ALL_TIME",
];

export function DateRangeFilter({ value, onChange }: DateRangeFilterProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [draftRange, setDraftRange] = useState<CalendarRange>(() =>
    calendarRangeFromFilter(value),
  );
  const [draftPreset, setDraftPreset] = useState<DateFilterPreset | undefined>(
    value.preset,
  );
  const [focusedValue, setFocusedValue] = useState<CalendarDate | null>(
    draftRange.start,
  );

  function openChanged(open: boolean) {
    setIsOpen(open);
    if (open) {
      const nextDraftRange = calendarRangeFromFilter(value);
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
    const nextRange = calendarRangeForPreset(preset, new Date());
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

  return (
    <section className="date-filter-panel" aria-label="Transaction filters">
      <div className="date-filter-control">
        <Button
          aria-label="Previous date range"
          color="tertiary"
          size="sm"
          iconLeading={ChevronLeft}
          onPress={() => onChange(previousMonthFilter(value))}
        />
        <AriaDialogTrigger isOpen={isOpen} onOpenChange={openChanged}>
          <Button
            color="tertiary"
            size="md"
            className="date-filter-label-button"
          >
            {value.label}
          </Button>
          <AriaPopover
            placement="bottom left"
            offset={8}
            className="date-filter-popover"
          >
            <AriaDialog
              aria-label="Date range filter"
              className="date-filter-dialog"
            >
              <div className="date-filter-presets">
                {presetOrder.map((preset) => (
                  <button
                    key={preset}
                    type="button"
                    className={cx(
                      "date-filter-preset",
                      draftPreset === preset && "date-filter-preset-selected",
                    )}
                    onClick={() => applyPreset(preset)}
                  >
                    {presetLabels[preset]}
                  </button>
                ))}
              </div>
              <div className="date-filter-calendar-panel">
                <RangeCalendar
                  value={draftRange}
                  focusedValue={focusedValue}
                  onFocusChange={(date) =>
                    setFocusedValue(date as CalendarDate | null)
                  }
                  onChange={(range) => {
                    setDraftPreset(undefined);
                    setDraftRange({
                      start: range.start as CalendarDate,
                      end: range.end as CalendarDate,
                    });
                  }}
                  highlightedDates={[today(getLocalTimeZone())]}
                />
                <div className="date-filter-dialog-footer">
                  <div className="date-filter-selected-range">
                    {draftPreset === "ALL_TIME"
                      ? "All time"
                      : `${formatFullDate(calendarDateToIsoDate(draftRange.start))} - ${formatFullDate(calendarDateToIsoDate(draftRange.end))}`}
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
          </AriaPopover>
        </AriaDialogTrigger>
        <Button
          aria-label="Next date range"
          color="tertiary"
          size="sm"
          iconLeading={ChevronRight}
          onPress={() => onChange(nextMonthFilter(value))}
        />
      </div>
    </section>
  );
}

export function createDefaultTransactionDateFilter(now: Date) {
  return filterForPreset("THIS_MONTH", now);
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
    label: presetLabels[preset],
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

  const start = currentDate.set({ month: 1, day: 1 });
  return { start, end: currentDate.set({ month: 12, day: 31 }) };
}

function calendarRangeFromFilter(value: TransactionDateFilterValue) {
  if (value.from && value.to) {
    return {
      start: isoDateToCalendarDate(value.from),
      end: isoDateToCalendarDate(value.to),
    };
  }

  return calendarRangeForPreset("THIS_MONTH", new Date());
}

function monthNavigationBase(value: TransactionDateFilterValue) {
  return value.from
    ? isoDateToCalendarDate(value.from)
    : dateToCalendarDate(new Date());
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
