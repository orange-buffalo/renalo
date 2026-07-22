import { ChevronDown, SearchLg } from "@untitledui/icons";
import { useEffect, useRef, useState } from "react";
import {
  Button as AriaButton,
  Autocomplete,
  type Selection,
} from "react-aria-components";
import { Dropdown } from "@/components/untitled/base/dropdown/dropdown";
import { Input } from "@/components/untitled/base/input/input";
import { Label } from "@/components/untitled/base/input/label";

export type SearchableDropdownItem = {
  id: string;
  label: string;
  supportingText?: string;
};

type SearchableDropdownProps = {
  label: string;
  placeholder: string;
  items: SearchableDropdownItem[];
  selectedKey?: string | null;
  isRequired?: boolean;
  isDisabled?: boolean;
  isInvalid?: boolean;
  hint?: string;
  searchPlaceholder?: string;
  className?: string;
  onSelectionChange: (key: string) => void;
};

export function SearchableDropdown({
  label,
  placeholder,
  items,
  selectedKey,
  isRequired,
  isDisabled,
  isInvalid,
  hint,
  searchPlaceholder = "Search",
  className,
  onSelectionChange,
}: SearchableDropdownProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [search, setSearch] = useState("");
  const searchInputRef = useDropdownSearchFocus(isOpen);
  const selectedItem = items.find((item) => item.id === selectedKey);

  return (
    <div className={className ?? "searchable-dropdown-field"}>
      <Label isRequired={isRequired}>{label}</Label>
      <Dropdown.Root
        isOpen={isOpen}
        onOpenChange={(open) => {
          setIsOpen(open);
          if (!open) {
            setSearch("");
          }
        }}
      >
        <AriaButton
          aria-label={label}
          className="searchable-dropdown-trigger"
          isDisabled={isDisabled}
          data-invalid={isInvalid || undefined}
        >
          <span className="searchable-dropdown-value">
            <span>{selectedItem?.label ?? placeholder}</span>
            {selectedItem?.supportingText && (
              <span>{selectedItem.supportingText}</span>
            )}
          </span>
          <ChevronDown
            aria-hidden="true"
            className="searchable-dropdown-chevron"
          />
        </AriaButton>
        <Dropdown.Popover
          placement="bottom left"
          className="searchable-dropdown-popover"
        >
          <Autocomplete
            inputValue={search}
            onInputChange={setSearch}
            filter={filterDropdownItem}
          >
            <div
              className="searchable-dropdown-search-wrap"
              onKeyDownCapture={(event) => {
                if (event.key === "Escape") {
                  event.preventDefault();
                  setIsOpen(false);
                }
              }}
            >
              <Input
                ref={searchInputRef}
                aria-label={`Search ${label.toLowerCase()}`}
                size="sm"
                placeholder={searchPlaceholder}
                icon={SearchLg}
              />
            </div>
            <Dropdown.Menu
              autoFocus="first"
              shouldFocusWrap
              selectionMode="single"
              selectedKeys={selectedKey ? [selectedKey] : []}
              onAction={(key) => {
                onSelectionChange(String(key));
                setIsOpen(false);
                setSearch("");
              }}
              className="searchable-dropdown-menu"
              renderEmptyState={() => (
                <p className="searchable-dropdown-empty">No matches</p>
              )}
            >
              {items.map((item) => (
                <Dropdown.Item
                  key={item.id}
                  id={item.id}
                  textValue={getItemTextValue(item)}
                >
                  <span className="searchable-dropdown-option">
                    <span>{item.label}</span>
                    {item.supportingText && <span>{item.supportingText}</span>}
                  </span>
                </Dropdown.Item>
              ))}
            </Dropdown.Menu>
          </Autocomplete>
        </Dropdown.Popover>
      </Dropdown.Root>
      {isInvalid && hint && <p className="searchable-dropdown-error">{hint}</p>}
    </div>
  );
}

type SearchableMultiDropdownProps = {
  label: string;
  placeholder: string;
  items: SearchableDropdownItem[];
  selectedKeys: string[];
  searchPlaceholder?: string;
  onSelectionChange: (keys: string[]) => void;
};

export function SearchableMultiDropdown({
  label,
  placeholder,
  items,
  selectedKeys,
  searchPlaceholder = "Search",
  onSelectionChange,
}: SearchableMultiDropdownProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [search, setSearch] = useState("");
  const searchInputRef = useDropdownSearchFocus(isOpen);
  const selectedKeySet = new Set(selectedKeys);

  function handleSelectionChange(selection: Selection) {
    if (selection === "all") {
      onSelectionChange(items.map((item) => item.id));
      return;
    }
    onSelectionChange(Array.from(selection).map(String));
  }

  return (
    <div className="transaction-filter-field">
      <span className="transaction-filter-field-label">{label}</span>
      <Dropdown.Root
        isOpen={isOpen}
        onOpenChange={(open) => {
          setIsOpen(open);
          if (!open) {
            setSearch("");
          }
        }}
      >
        <AriaButton
          aria-label={label}
          className="searchable-dropdown-trigger transaction-filter-select-trigger"
        >
          <span className="searchable-dropdown-value">
            <span>
              {selectedKeys.length > 0
                ? `${selectedKeys.length} selected`
                : placeholder}
            </span>
          </span>
          <ChevronDown
            aria-hidden="true"
            className="searchable-dropdown-chevron"
          />
        </AriaButton>
        <Dropdown.Popover
          placement="bottom left"
          className="searchable-dropdown-popover transaction-filter-select-popover"
        >
          <Autocomplete
            inputValue={search}
            onInputChange={setSearch}
            filter={filterDropdownItem}
          >
            <div
              className="searchable-dropdown-search-wrap"
              onKeyDownCapture={(event) => {
                if (event.key === "Escape") {
                  event.preventDefault();
                  setIsOpen(false);
                }
              }}
            >
              <Input
                ref={searchInputRef}
                aria-label={`Search ${label.toLowerCase()}`}
                size="sm"
                placeholder={searchPlaceholder}
                icon={SearchLg}
              />
            </div>
            <Dropdown.Menu
              autoFocus="first"
              shouldFocusWrap
              shouldCloseOnSelect={false}
              selectionMode="multiple"
              selectionBehavior="toggle"
              selectedKeys={selectedKeySet}
              onSelectionChange={handleSelectionChange}
              className="searchable-dropdown-menu"
              renderEmptyState={() => (
                <p className="searchable-dropdown-empty">No matches</p>
              )}
            >
              {items.map((item) => (
                <Dropdown.Item
                  key={item.id}
                  id={item.id}
                  textValue={getItemTextValue(item)}
                >
                  <span className="searchable-dropdown-option">
                    <span>{item.label}</span>
                    {item.supportingText && <span>{item.supportingText}</span>}
                  </span>
                </Dropdown.Item>
              ))}
            </Dropdown.Menu>
          </Autocomplete>
        </Dropdown.Popover>
      </Dropdown.Root>
    </div>
  );
}

function useDropdownSearchFocus(isOpen: boolean) {
  const searchInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!isOpen) {
      return;
    }

    const focusFrame = requestAnimationFrame(() =>
      searchInputRef.current?.focus(),
    );
    return () => cancelAnimationFrame(focusFrame);
  }, [isOpen]);

  return searchInputRef;
}

function getItemTextValue(item: SearchableDropdownItem) {
  return [item.label, item.supportingText].filter(Boolean).join(" ");
}

function filterDropdownItem(textValue: string, inputValue: string) {
  return textValue.toLowerCase().includes(inputValue.trim().toLowerCase());
}
