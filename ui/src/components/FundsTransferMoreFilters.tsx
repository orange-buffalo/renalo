import { XClose } from "@untitledui/icons";
import { useState } from "react";
import {
  Dialog as AriaDialog,
  DialogTrigger as AriaDialogTrigger,
  Modal as AriaModal,
  ModalOverlay as AriaModalOverlay,
  Popover as AriaPopover,
} from "react-aria-components";
import { FilterIcon } from "@/components/FilterIcon";
import { MultiSelectFilter } from "@/components/MultiSelectFilter";
import { Button } from "@/components/untitled/base/buttons/button";
import { useBreakpoint } from "@/hooks/use-breakpoint";

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
  const isDesktop = useBreakpoint("md");
  const [isOpen, setIsOpen] = useState(false);
  const activeFilterCount = getActiveFilterCount(value);

  function update(nextValue: Partial<FundsTransferSecondaryFilters>) {
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
            onPress={() => onChange(emptyFundsTransferSecondaryFilters)}
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
          label="Source account"
          allLabel="All source accounts"
          options={accounts}
          selectedIds={value.sourceAccountIds}
          onChange={(sourceAccountIds) => update({ sourceAccountIds })}
        />
        <MultiSelectFilter
          label="Target account"
          allLabel="All target accounts"
          options={accounts}
          selectedIds={value.targetAccountIds}
          onChange={(targetAccountIds) => update({ targetAccountIds })}
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
          iconLeading={<FilterIcon activeFilterCount={activeFilterCount} />}
        >
          <span className="transaction-more-filters-label">More filters</span>
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

function getActiveFilterCount(value: FundsTransferSecondaryFilters) {
  return (
    (value.sourceAccountIds.length > 0 ? 1 : 0) +
    (value.targetAccountIds.length > 0 ? 1 : 0)
  );
}
