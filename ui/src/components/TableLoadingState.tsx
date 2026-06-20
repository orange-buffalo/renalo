import { LoadingIndicator } from "@/components/untitled/application/loading-indicator/loading-indicator";

type TableLoadingStateProps = {
  label: string;
};

export function TableLoadingState({ label }: TableLoadingStateProps) {
  return (
    <div
      className="table-loading-state"
      role="status"
      aria-busy="true"
      aria-label={label}
    >
      <LoadingIndicator type="line-simple" size="sm" />
    </div>
  );
}
