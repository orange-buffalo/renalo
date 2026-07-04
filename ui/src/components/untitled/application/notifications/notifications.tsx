import { CheckCircle, InfoCircle, XClose } from "@untitledui/icons";
import type { ReactNode } from "react";
import { Toaster as SonnerToaster, toast } from "sonner";
import { cx } from "@/utils/cx";

type NotificationTone = "success" | "info";

const toneStyles: Record<
  NotificationTone,
  { icon: string; ringInner: string; ringOuter: string }
> = {
  success: {
    icon: "text-[#12b76a]",
    ringInner: "ring-[#a6f4c5]",
    ringOuter: "ring-[#ecfdf5]",
  },
  info: {
    icon: "text-[#53b1fd]",
    ringInner: "ring-[#b2ddff]",
    ringOuter: "ring-[#eff8ff]",
  },
};

const icons = {
  success: CheckCircle,
  info: InfoCircle,
};

export function Notifications() {
  return (
    <SonnerToaster
      position="top-right"
      offset={{ top: 32, right: 24 }}
      toastOptions={{
        unstyled: true,
        duration: 10_000,
        classNames: {
          toast: "w-full max-w-lg",
        },
      }}
    />
  );
}

export function showNotification({
  title,
  description,
  tone = "success",
}: {
  title: string;
  description?: ReactNode;
  tone?: NotificationTone;
}) {
  const Icon = icons[tone];
  const styles = toneStyles[tone];
  toast.custom(
    (toastId) => (
      <div
        className="flex w-full gap-4 rounded-2xl border border-[#d8dce5] bg-white p-5 pr-4 shadow-lg"
        role="status"
      >
        <span
          className={cx(
            "mt-1 flex size-7 shrink-0 items-center justify-center rounded-full ring-4",
            styles.ringInner,
          )}
        >
          <span
            className={cx(
              "flex size-6 items-center justify-center rounded-full ring-4",
              styles.ringOuter,
            )}
          >
            <Icon className={cx("size-5", styles.icon)} />
          </span>
        </span>
        <div className="grid min-w-0 flex-1 gap-2">
          <p className="m-0 text-lg font-semibold text-[#1d2029]">{title}</p>
          {description && (
            <p className="m-0 text-base leading-6 text-[#364054]">
              {description}
            </p>
          )}
        </div>
        <button
          type="button"
          aria-label="Dismiss notification"
          className="-m-1 size-8 shrink-0 rounded-lg p-1.5 text-[#9aa5b8] transition hover:bg-[#f7f8fa] hover:text-[#4f5d74]"
          onClick={() => toast.dismiss(toastId)}
        >
          <XClose className="size-5" />
        </button>
      </div>
    ),
    { duration: 10_000, id: `${tone}:${title}:${description ?? ""}` },
  );
}
