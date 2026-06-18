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
    icon: "text-[#17b26a]",
    ringInner: "ring-[#dcfae6]",
    ringOuter: "ring-[#ecfdf3]",
  },
  brand: {
    icon: "text-[#2e90fa]",
    ringInner: "ring-[#d1e9ff]",
    ringOuter: "ring-[#eff8ff]",
  },
  gray: {
    icon: "text-[#717680]",
    ringInner: "ring-[#e9eaeb]",
    ringOuter: "ring-[#fafafa]",
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
        "flex gap-5 rounded-2xl border border-[#d5d7da] bg-white p-5 shadow-xs",
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
        <p className="m-0 font-semibold text-[#414651]">{title}</p>
        {children && (
          <div className="grid gap-3 text-[#414651] [&_p]:m-0">{children}</div>
        )}
      </div>
    </div>
  );
}
