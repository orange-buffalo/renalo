import { FilterLines } from "@untitledui/icons";
import { useState } from "react";
import {
  Dialog as AriaDialog,
  DialogTrigger as AriaDialogTrigger,
  Popover as AriaPopover,
} from "react-aria-components";
import { SearchableMultiDropdown } from "@/components/SearchableDropdown";
import { Button } from "@/components/untitled/base/buttons/button";
import { Tag, TagGroup, TagList } from "@/components/untitled/base/tags/tags";

export type FundsTransferFilterOption = {
  id: number;
  name: string;
};

export type FundsTransferSecondaryFilters = {
  sourceAccountIds: number[];
  targetAccountIds: number[];
};

type FundsTransferMoreFiltersProps = {
  value: FundsTransferSecondaryFilters;
  accounts: FundsTransferFilterOption[];
  onChange: (value: FundsTransferSecondaryFilters) => void;
};

export const emptyFundsTransferSecondaryFilters: FundsTransferSecondaryFilters =
  {
    sourceAccountIds: [],
    targetAccountIds: [],
  };

export function FundsTransferMoreFilters({
  value,
  accounts,
  onChange,
}: FundsTransferMoreFiltersProps) {
  const [isOpen, setIsOpen] = useState(false);
  const activeFilterCount = getActiveFilterCount(value);

  function update(nextValue: Partial<FundsTransferSecondaryFilters>) {
    onChange({ ...value, ...nextValue });
  }

  return (
    <div className="transaction-more-filters">
      <AriaDialogTrigger isOpen={isOpen} onOpenChange={setIsOpen}>
        <Button
          aria-label="More filters"
          color="tertiary"
          size="sm"
          iconLeading={FilterLines}
        >
          <span className="transaction-more-filters-label">More filters</span>
          {activeFilterCount > 0 && (
            <span className="transaction-filter-count-badge">
              {activeFilterCount}
            </span>
          )}
        </Button>
        <AriaPopover className="transaction-more-filters-popover" offset={8}>
          <AriaDialog className="transaction-more-filters-dialog">
            <div className="transaction-more-filters-header">
              <h2>More filters</h2>
              <Button
                color="text-gray"
                size="sm"
                onPress={() => onChange(emptyFundsTransferSecondaryFilters)}
                isDisabled={activeFilterCount === 0}
              >
                Clear all
              </Button>
            </div>

            <div className="transaction-more-filters-form">
              <MultiSelectFilter
                label="Source account"
                options={accounts}
                selectedIds={value.sourceAccountIds}
                onChange={(sourceAccountIds) => update({ sourceAccountIds })}
              />
              <MultiSelectFilter
                label="Target account"
                options={accounts}
                selectedIds={value.targetAccountIds}
                onChange={(targetAccountIds) => update({ targetAccountIds })}
              />
            </div>
          </AriaDialog>
        </AriaPopover>
      </AriaDialogTrigger>
    </div>
  );
}

function MultiSelectFilter({
  label,
  options,
  selectedIds,
  onChange,
}: {
  label: string;
  options: FundsTransferFilterOption[];
  selectedIds: number[];
  onChange: (selectedIds: number[]) => void;
}) {
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
        placeholder={`Choose ${label.toLowerCase()}`}
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

function getActiveFilterCount(value: FundsTransferSecondaryFilters) {
  return (
    (value.sourceAccountIds.length > 0 ? 1 : 0) +
    (value.targetAccountIds.length > 0 ? 1 : 0)
  );
}
