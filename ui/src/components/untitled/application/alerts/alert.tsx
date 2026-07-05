import { AlertCircle } from "@untitledui/icons";
import type { ReactNode } from "react";
import { cx } from "@/utils/cx";

type AlertTone = "warning" | "error" | "success" | "brand" | "gray";

const toneStyles: Record<
  AlertTone,
  { icon: string; ringInner: string; ringOuter: string }
> = {
  warning: {
    icon: "text-utility-yellow-500",
    ringInner: "ring-utility-yellow-200",
    ringOuter: "ring-utility-yellow-50",
  },
  error: {
    icon: "text-fg-error-secondary",
    ringInner: "ring-utility-red-200",
    ringOuter: "ring-utility-red-50",
  },
  success: {
    icon: "text-utility-green-500",
    ringInner: "ring-utility-green-200",
    ringOuter: "ring-utility-green-50",
  },
  brand: {
    icon: "text-utility-brand-500",
    ringInner: "ring-utility-brand-200",
    ringOuter: "ring-utility-brand-50",
  },
  gray: {
    icon: "text-utility-slate-500",
    ringInner: "ring-utility-slate-200",
    ringOuter: "ring-utility-slate-50",
  },
};

export function Alert({
  tone = "gray",
  title,
  children,
  className,
}: {
  tone?: AlertTone;
  title: string;
  children?: ReactNode;
  className?: string;
}) {
  const styles = toneStyles[tone];

  return (
    <div
      role="alert"
      className={cx(
        "flex gap-5 rounded-2xl border border-border-secondary bg-white p-5 shadow-xs",
        className,
      )}
    >
      <span
        className={cx(
          "mt-1 flex size-7 shrink-0 items-center justify-center rounded-full ring-4",
          styles.ringInner,
        )}
      >
        <span
          className={cx(
            "flex size-5 items-center justify-center rounded-full ring-4",
            styles.ringOuter,
          )}
        >
          <AlertCircle className={cx("size-4", styles.icon)} />
        </span>
      </span>
      <div className="grid min-w-0 flex-1 gap-1 text-base">
        <p className="m-0 font-semibold text-secondary">{title}</p>
        {children && (
          <div className="grid gap-3 text-secondary [&_p]:m-0">{children}</div>
        )}
      </div>
    </div>
  );
}
