import { SearchableMultiDropdown } from "@/components/SearchableDropdown";
import { Tag, TagGroup, TagList } from "@/components/untitled/base/tags/tags";

export type MultiSelectFilterOption = {
  id: number;
  name: string;
};

type MultiSelectFilterProps = {
  label: string;
  allLabel: string;
  options: MultiSelectFilterOption[];
  selectedIds: number[];
  onChange: (selectedIds: number[]) => void;
};

export function MultiSelectFilter({
  label,
  allLabel,
  options,
  selectedIds,
  onChange,
}: MultiSelectFilterProps) {
  const selectedOptions = options.filter((option) =>
    selectedIds.includes(option.id),
  );

  function removeOption(optionId: string) {
    onChange(
      selectedIds.filter((selectedId) => selectedId !== Number(optionId)),
    );
  }

  return (
    <>
      <SearchableMultiDropdown
        label={label}
        allLabel={allLabel}
        items={options.map((option) => ({
          id: String(option.id),
          label: option.name,
        }))}
        selectedKeys={selectedIds.map(String)}
        onSelectionChange={(nextSelectedIds) =>
          onChange(nextSelectedIds.map(Number))
        }
      />
      {selectedOptions.length > 0 && (
        <TagGroup label={`Selected ${label.toLowerCase()}`} size="sm">
          <TagList
            className="transaction-filter-tags"
            items={selectedOptions.map((option) => ({
              id: option.id.toString(),
              label: option.name,
            }))}
          >
            {(item) => (
              <Tag id={item.id} onClose={removeOption}>
                {item.label}
              </Tag>
            )}
          </TagList>
        </TagGroup>
      )}
    </>
  );
}
