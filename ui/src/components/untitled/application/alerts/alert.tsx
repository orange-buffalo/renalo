import type { ReactNode } from "react";
import { cx } from "@/utils/cx";

type AlertTone = "warning" | "error" | "success" | "brand" | "gray";

const toneStyles: Record<AlertTone, string> = {
  warning: "border-[#fedf89] bg-[#fffaeb] text-[#93370d]",
  error: "border-[#fecdca] bg-[#fef3f2] text-[#b42318]",
  success: "border-[#abefc6] bg-[#ecfdf3] text-[#067647]",
  brand: "border-[#b2ddff] bg-[#eff8ff] text-[#175cd3]",
  gray: "border-[#eaecf0] bg-[#f9fafb] text-[#475467]",
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
  return (
    <div
      role="alert"
      className={cx(
        "grid gap-2 rounded-2xl border p-4 text-sm",
        toneStyles[tone],
        className,
      )}
    >
      <p className="m-0 font-semibold text-primary">{title}</p>
      {children && <div className="grid gap-3 text-sm">{children}</div>}
    </div>
  );
}
