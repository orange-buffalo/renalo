import { ChevronDown, FilterLines, SearchLg } from "@untitledui/icons";
import { useState } from "react";
import {
  Dialog as AriaDialog,
  DialogTrigger as AriaDialogTrigger,
  Popover as AriaPopover,
} from "react-aria-components";
import { Button } from "@/components/untitled/base/buttons/button";
import { Checkbox } from "@/components/untitled/base/checkbox/checkbox";
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
        <Button color="tertiary" size="sm" iconLeading={FilterLines}>
          More filters
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
                color="link-gray"
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
  const [isOpen, setIsOpen] = useState(false);
  const [search, setSearch] = useState("");
  const selectedOptions = options.filter((option) =>
    selectedIds.includes(option.id),
  );
  const visibleOptions = options.filter((option) =>
    option.name.toLowerCase().includes(search.trim().toLowerCase()),
  );

  function toggleOption(optionId: number) {
    if (selectedIds.includes(optionId)) {
      onChange(selectedIds.filter((selectedId) => selectedId !== optionId));
    } else {
      onChange([...selectedIds, optionId]);
    }
  }

  function removeOption(optionId: string) {
    onChange(
      selectedIds.filter((selectedId) => selectedId !== Number(optionId)),
    );
  }

  return (
    <div className="transaction-filter-field">
      <span className="transaction-filter-field-label">{label}</span>
      <AriaDialogTrigger isOpen={isOpen} onOpenChange={setIsOpen}>
        <Button
          color="secondary"
          size="sm"
          iconTrailing={ChevronDown}
          className="transaction-filter-select-trigger"
        >
          {selectedOptions.length > 0
            ? `${selectedOptions.length} selected`
            : `Choose ${label.toLowerCase()}`}
        </Button>
        <AriaPopover className="transaction-filter-select-popover" offset={6}>
          <AriaDialog className="transaction-filter-select-dialog">
            <Input
              aria-label={`Search ${label.toLowerCase()}`}
              size="sm"
              placeholder="Search"
              icon={SearchLg}
              value={search}
              onChange={setSearch}
              className="transaction-filter-search"
            />
            <div className="transaction-filter-options">
              {visibleOptions.map((option) => (
                <Checkbox
                  key={option.id}
                  label={option.name}
                  isSelected={selectedIds.includes(option.id)}
                  onChange={() => toggleOption(option.id)}
                  className="transaction-filter-option"
                />
              ))}
              {visibleOptions.length === 0 && (
                <p className="transaction-filter-empty-option">No matches</p>
              )}
            </div>
          </AriaDialog>
        </AriaPopover>
      </AriaDialogTrigger>
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
    </div>
  );
}

function getActiveFilterCount(value: TransactionSecondaryFilters) {
  return (
    (value.categoryIds.length > 0 ? 1 : 0) +
    (value.accountIds.length > 0 ? 1 : 0) +
    (value.notes.trim() ? 1 : 0)
  );
}
