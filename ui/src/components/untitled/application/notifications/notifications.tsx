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
    icon: "text-utility-green-500",
    ringInner: "ring-utility-green-200",
    ringOuter: "ring-utility-green-50",
  },
  info: {
    icon: "text-utility-sky-400",
    ringInner: "ring-utility-sky-200",
    ringOuter: "ring-utility-sky-50",
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
        className="flex w-full gap-4 rounded-2xl border border-border-secondary bg-white p-5 pr-4 shadow-lg"
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
          <p className="m-0 text-lg font-semibold text-primary">{title}</p>
          {description && (
            <p className="m-0 text-base leading-6 text-secondary">
              {description}
            </p>
          )}
        </div>
        <button
          type="button"
          aria-label="Dismiss notification"
          className="-m-1 size-8 shrink-0 rounded-lg p-1.5 text-utility-neutral-400 transition hover:bg-secondary hover:text-tertiary"
          onClick={() => toast.dismiss(toastId)}
        >
          <XClose className="size-5" />
        </button>
      </div>
    ),
    { duration: 10_000, id: `${tone}:${title}:${description ?? ""}` },
  );
}
