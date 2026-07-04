import { AlertCircle } from "@untitledui/icons";
import type { ReactNode } from "react";
import { cx } from "@/utils/cx";

type AlertTone = "warning" | "error" | "success" | "brand" | "gray";

const toneStyles: Record<
  AlertTone,
  { icon: string; ringInner: string; ringOuter: string }
> = {
  warning: {
    icon: "text-[#dc6803]",
    ringInner: "ring-[#fef0c7]",
    ringOuter: "ring-[#fffaeb]",
  },
  error: {
    icon: "text-[#f04438]",
    ringInner: "ring-[#fee4e2]",
    ringOuter: "ring-[#fef3f2]",
  },
  success: {
    icon: "text-[#12b76a]",
    ringInner: "ring-[#a6f4c5]",
    ringOuter: "ring-[#ecfdf5]",
  },
  brand: {
    icon: "text-[#6941c6]",
    ringInner: "ring-[#c4b0f7]",
    ringOuter: "ring-[#f5eeff]",
  },
  gray: {
    icon: "text-[#6c7a92]",
    ringInner: "ring-[#d8dce5]",
    ringOuter: "ring-[#f7f8fa]",
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
        "flex gap-5 rounded-2xl border border-[#d8dce5] bg-white p-5 shadow-xs",
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
        <p className="m-0 font-semibold text-[#364054]">{title}</p>
        {children && (
          <div className="grid gap-3 text-[#364054] [&_p]:m-0">{children}</div>
        )}
      </div>
    </div>
  );
}
