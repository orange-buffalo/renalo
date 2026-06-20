import { LoadingIndicator } from "@/components/untitled/application/loading-indicator/loading-indicator";

type FormLoadingOverlayProps = {
  isLoading: boolean;
  label?: string;
};

export function FormLoadingOverlay({
  isLoading,
  label = "Loading...",
}: FormLoadingOverlayProps) {
  if (!isLoading) {
    return null;
  }

  return (
    <div className="form-loading-overlay" aria-live="polite" aria-busy="true">
      <LoadingIndicator type="dot-circle" size="md" label={label} />
    </div>
  );
}
