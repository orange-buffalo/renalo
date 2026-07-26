import { FilterLines, XClose } from "@untitledui/icons";
import { useState } from "react";
import {
  Dialog as AriaDialog,
  DialogTrigger as AriaDialogTrigger,
  Modal as AriaModal,
  ModalOverlay as AriaModalOverlay,
  Popover as AriaPopover,
} from "react-aria-components";
import { MultiSelectFilter } from "@/components/MultiSelectFilter";
import { Button } from "@/components/untitled/base/buttons/button";
import { Input } from "@/components/untitled/base/input/input";
import { useBreakpoint } from "@/hooks/use-breakpoint";

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
  const isDesktop = useBreakpoint("md");
  const [isOpen, setIsOpen] = useState(false);
  const activeFilterCount = getActiveFilterCount(value);

  function update(nextValue: Partial<TransactionSecondaryFilters>) {
    onChange({ ...value, ...nextValue });
  }

  const filtersDialog = (
    <AriaDialog
      aria-label="More filters"
      className="transaction-more-filters-dialog"
    >
      <div className="transaction-more-filters-header">
        <h2>More filters</h2>
        <div className="transaction-more-filters-header-actions">
          <Button
            color="text-gray"
            size="sm"
            onPress={() => onChange(emptyTransactionSecondaryFilters)}
            isDisabled={activeFilterCount === 0}
          >
            Clear all
          </Button>
          <Button
            aria-label="Close filters"
            color="tertiary"
            size="sm"
            iconLeading={XClose}
            className="transaction-more-filters-close"
            onPress={() => setIsOpen(false)}
          />
        </div>
      </div>

      <div className="transaction-more-filters-form">
        <MultiSelectFilter
          label={categoryLabel}
          allLabel={
            categoryLabel === "Category"
              ? "All categories"
              : "All income categories"
          }
          options={categories}
          selectedIds={value.categoryIds}
          onChange={(categoryIds) => update({ categoryIds })}
        />
        <MultiSelectFilter
          label="Account"
          allLabel="All accounts"
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
  );

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
          <span className="transaction-filter-count-badge">
            {activeFilterCount || null}
          </span>
        </Button>
        {isDesktop ? (
          <AriaPopover className="transaction-more-filters-popover" offset={8}>
            {filtersDialog}
          </AriaPopover>
        ) : (
          <AriaModalOverlay
            isDismissable
            className="transaction-more-filters-mobile-overlay"
          >
            <AriaModal className="transaction-more-filters-mobile-modal">
              {filtersDialog}
            </AriaModal>
          </AriaModalOverlay>
        )}
      </AriaDialogTrigger>
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
