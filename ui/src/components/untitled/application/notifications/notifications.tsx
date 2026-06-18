import { CheckCircle, InfoCircle } from "@untitledui/icons";
import type { ReactNode } from "react";
import { Toaster as SonnerToaster, toast } from "sonner";
import { cx } from "@/utils/cx";

type NotificationTone = "success" | "info";

const toneStyles: Record<NotificationTone, string> = {
  success: "border-[#abefc6] bg-[#ecfdf3] text-[#067647]",
  info: "border-[#b2ddff] bg-[#eff8ff] text-[#175cd3]",
};

const icons = {
  success: CheckCircle,
  info: InfoCircle,
};

export function Notifications() {
  return (
    <SonnerToaster
      position="top-right"
      toastOptions={{
        unstyled: true,
        duration: 20_000,
        classNames: {
          toast: "w-full max-w-sm",
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
  toast.custom(
    () => (
      <div
        className={cx(
          "flex gap-3 rounded-xl border p-4 shadow-lg ring-1 ring-black/5",
          toneStyles[tone],
        )}
        role="status"
      >
        <Icon className="size-5 shrink-0" />
        <div className="grid gap-1">
          <p className="m-0 text-sm font-semibold text-primary">{title}</p>
          {description && <p className="m-0 text-sm text-tertiary">{description}</p>}
        </div>
      </div>
    ),
    { duration: 20_000, id: `${tone}:${title}:${description ?? ""}` },
  );
}
