"use client";

import { getLocalTimeZone, today } from "@internationalized/date";
import { useControlledState } from "@react-stately/utils";
import { Calendar as CalendarIcon } from "@untitledui/icons";
import { useDateFormatter } from "react-aria";
import type { DatePickerProps as AriaDatePickerProps, DateValue } from "react-aria-components";
import { DatePicker as AriaDatePicker, Dialog as AriaDialog, Group as AriaGroup, Popover as AriaPopover } from "react-aria-components";
import { Button, type ButtonProps } from "@/components/untitled/base/buttons/button";
import { cx } from "@/utils/cx";
import { Calendar } from "./calendar";

const highlightedDates = [today(getLocalTimeZone())];

interface DatePickerProps extends AriaDatePickerProps<DateValue> {
    /** @deprecated Dates are applied immediately on selection. */
    onApply?: () => void;
    /** @deprecated Dates are applied immediately on selection. */
    onCancel?: () => void;
    size?: ButtonProps["size"];
}

export const DatePicker = ({ value: valueProp, defaultValue, onChange, onApply, onCancel, size = "sm", ...props }: DatePickerProps) => {
    const formatter = useDateFormatter({
        month: "short",
        day: "numeric",
        year: "numeric",
    });
    const [value, setValue] = useControlledState(valueProp, defaultValue || null, onChange);

    const formattedDate = value ? formatter.format(value.toDate(getLocalTimeZone())) : "Select date";

    function handleChange(nextValue: DateValue | null) {
        setValue(nextValue);
        if (nextValue) {
            onApply?.();
        }
    }

    return (
        <AriaDatePicker aria-label="Date picker" shouldCloseOnSelect {...props} value={value} onChange={handleChange}>
            <AriaGroup>
                <Button size={size} color="secondary" iconLeading={CalendarIcon}>
                    {formattedDate}
                </Button>
            </AriaGroup>
            <AriaPopover
                offset={8}
                placement="bottom right"
                className={({ isEntering, isExiting }) =>
                    cx(
                        "origin-(--trigger-anchor-point) will-change-transform",
                        isEntering &&
                            "duration-150 ease-out animate-in fade-in placement-right:slide-in-from-left-0.5 placement-top:slide-in-from-bottom-0.5 placement-bottom:slide-in-from-top-0.5",
                        isExiting &&
                            "duration-100 ease-in animate-out fade-out placement-right:slide-out-to-left-0.5 placement-top:slide-out-to-bottom-0.5 placement-bottom:slide-out-to-top-0.5",
                    )
                }
            >
                <AriaDialog aria-label="Date picker" className="rounded-xl bg-primary p-3 shadow-xl ring ring-secondary_alt sm:p-4">
                    <Calendar highlightedDates={highlightedDates} className="gap-2" />
                </AriaDialog>
            </AriaPopover>
        </AriaDatePicker>
    );
};
