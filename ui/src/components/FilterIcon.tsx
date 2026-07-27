import { FilterLines } from "@untitledui/icons";

type FilterIconProps = {
  activeFilterCount: number;
};

export function FilterIcon({ activeFilterCount }: FilterIconProps) {
  return (
    <span data-icon="leading" className="transaction-filter-icon">
      <FilterLines />
      {activeFilterCount > 0 && (
        <span className="transaction-filter-count-badge">
          {activeFilterCount}
        </span>
      )}
    </span>
  );
}
