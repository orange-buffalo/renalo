import { LoadingIndicator } from "@/components/untitled/application/loading-indicator/loading-indicator";

type FormLoadingOverlayProps = {
  isLoading: boolean;
};

export function FormLoadingOverlay({ isLoading }: FormLoadingOverlayProps) {
  if (!isLoading) {
    return null;
  }

  return (
    <div
      className="form-loading-overlay"
      role="status"
      aria-live="polite"
      aria-busy="true"
      aria-label="Loading form"
    >
      <LoadingIndicator type="line-simple" size="md" />
    </div>
  );
}
