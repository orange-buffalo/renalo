import { SearchLg } from "@untitledui/icons";

type TableEmptyStateProps = {
  title: string;
};

export function TableEmptyState({ title }: TableEmptyStateProps) {
  return (
    <div className="table-empty-state">
      <div className="table-empty-state-icon" aria-hidden="true">
        <SearchLg />
      </div>
      <p>{title}</p>
    </div>
  );
}
