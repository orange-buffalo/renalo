import { FilterLines } from "@untitledui/icons";
import { useState } from "react";
import {
  Dialog as AriaDialog,
  DialogTrigger as AriaDialogTrigger,
  Popover as AriaPopover,
} from "react-aria-components";
import { SearchableMultiDropdown } from "@/components/SearchableDropdown";
import { Button } from "@/components/untitled/base/buttons/button";
import { Input } from "@/components/untitled/base/input/input";
import { Tag, TagGroup, TagList } from "@/components/untitled/base/tags/tags";

export type TransactionFilterOption = {
  id: number;
  name: string;
};

export type TransactionSecondaryFilters = {
  categoryIds: number[];
  accountIds: number[];
  notes: string;
};

type TransactionMoreFiltersProps = {
  value: TransactionSecondaryFilters;
  categories: TransactionFilterOption[];
  accounts: TransactionFilterOption[];
  categoryLabel: string;
  onChange: (value: TransactionSecondaryFilters) => void;
};

export const emptyTransactionSecondaryFilters: TransactionSecondaryFilters = {
  categoryIds: [],
  accountIds: [],
  notes: "",
};

export function TransactionMoreFilters({
  value,
  categories,
  accounts,
  categoryLabel,
  onChange,
}: TransactionMoreFiltersProps) {
  const [isOpen, setIsOpen] = useState(false);
  const activeFilterCount = getActiveFilterCount(value);

  function update(nextValue: Partial<TransactionSecondaryFilters>) {
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
                onPress={() => onChange(emptyTransactionSecondaryFilters)}
                isDisabled={activeFilterCount === 0}
              >
                Clear all
              </Button>
            </div>

            <div className="transaction-more-filters-form">
              <MultiSelectFilter
                label={categoryLabel}
                options={categories}
                selectedIds={value.categoryIds}
                onChange={(categoryIds) => update({ categoryIds })}
              />
              <MultiSelectFilter
                label="Account"
                options={accounts}
                selectedIds={value.accountIds}
                onChange={(accountIds) => update({ accountIds })}
              />
              <Input
                label="Notes"
                name="notes"
                size="sm"
                placeholder="Search notes"
                value={value.notes}
                onChange={(notes) => update({ notes })}
                className="transaction-filter-input"
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
  options: TransactionFilterOption[];
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

function getActiveFilterCount(value: TransactionSecondaryFilters) {
  return (
    (value.categoryIds.length > 0 ? 1 : 0) +
    (value.accountIds.length > 0 ? 1 : 0) +
    (value.notes.trim() ? 1 : 0)
  );
}
