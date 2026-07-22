import { ChevronDown, SearchLg } from "@untitledui/icons";
import {
  type KeyboardEvent,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { Button as AriaButton, type Selection } from "react-aria-components";
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
  const visibleItems = useFilteredItems(items, search);

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
          <div
            onKeyDownCapture={(event) =>
              handleMenuKeyDown(event, searchInputRef.current)
            }
          >
            <div className="searchable-dropdown-search-wrap">
              <Input
                ref={searchInputRef}
                aria-label={`Search ${label.toLowerCase()}`}
                size="sm"
                placeholder={searchPlaceholder}
                icon={SearchLg}
                value={search}
                onChange={setSearch}
                onKeyDown={handleSearchKeyDown}
              />
            </div>
            <Dropdown.Menu
              autoFocus={false}
              shouldFocusWrap={false}
              selectionMode="single"
              selectedKeys={selectedKey ? [selectedKey] : []}
              onAction={(key) => {
                onSelectionChange(String(key));
                setIsOpen(false);
                setSearch("");
              }}
              className="searchable-dropdown-menu"
            >
              {visibleItems.map((item) => (
                <Dropdown.Item
                  key={item.id}
                  id={item.id}
                  textValue={item.label}
                >
                  <span className="searchable-dropdown-option">
                    <span>{item.label}</span>
                    {item.supportingText && <span>{item.supportingText}</span>}
                  </span>
                </Dropdown.Item>
              ))}
            </Dropdown.Menu>
            {visibleItems.length === 0 && (
              <p className="searchable-dropdown-empty">No matches</p>
            )}
          </div>
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
  const visibleItems = useFilteredItems(items, search);
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
          <div
            onKeyDownCapture={(event) =>
              handleMenuKeyDown(event, searchInputRef.current)
            }
          >
            <div className="searchable-dropdown-search-wrap">
              <Input
                ref={searchInputRef}
                aria-label={`Search ${label.toLowerCase()}`}
                size="sm"
                placeholder={searchPlaceholder}
                icon={SearchLg}
                value={search}
                onChange={setSearch}
                onKeyDown={handleSearchKeyDown}
              />
            </div>
            <Dropdown.Menu
              autoFocus={false}
              shouldFocusWrap={false}
              shouldCloseOnSelect={false}
              selectionMode="multiple"
              selectedKeys={selectedKeySet}
              onSelectionChange={handleSelectionChange}
              className="searchable-dropdown-menu"
            >
              {visibleItems.map((item) => (
                <Dropdown.Item
                  key={item.id}
                  id={item.id}
                  textValue={item.label}
                >
                  <span className="searchable-dropdown-option">
                    <span>{item.label}</span>
                    {item.supportingText && <span>{item.supportingText}</span>}
                  </span>
                </Dropdown.Item>
              ))}
            </Dropdown.Menu>
            {visibleItems.length === 0 && (
              <p className="searchable-dropdown-empty">No matches</p>
            )}
          </div>
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

function handleSearchKeyDown(event: KeyboardEvent) {
  if (event.key === "Enter") {
    event.preventDefault();
    return;
  }

  if (event.key !== "ArrowDown" && event.key !== "ArrowUp") {
    return;
  }

  event.preventDefault();

  const menuItems = event.currentTarget
    .closest(".searchable-dropdown-popover")
    ?.querySelectorAll<HTMLElement>(MENU_ITEM_SELECTOR);

  if (!menuItems?.length) {
    return;
  }

  menuItems[event.key === "ArrowDown" ? 0 : menuItems.length - 1].focus();
}

function handleMenuKeyDown(
  event: KeyboardEvent,
  searchInput: HTMLInputElement | null,
) {
  if (!searchInput || (event.key !== "ArrowDown" && event.key !== "ArrowUp")) {
    return;
  }

  const menuItems = Array.from(
    event.currentTarget.querySelectorAll<HTMLElement>(MENU_ITEM_SELECTOR),
  );
  const focusedIndex = menuItems.indexOf(document.activeElement as HTMLElement);
  const isLeavingFirst = event.key === "ArrowUp" && focusedIndex === 0;
  const isLeavingLast =
    event.key === "ArrowDown" && focusedIndex === menuItems.length - 1;

  if (isLeavingFirst || isLeavingLast) {
    event.preventDefault();
    requestAnimationFrame(() => searchInput.focus());
  }
}

const MENU_ITEM_SELECTOR =
  '[role="menuitem"]:not([aria-disabled="true"]), [role="menuitemradio"]:not([aria-disabled="true"]), [role="menuitemcheckbox"]:not([aria-disabled="true"])';

function useFilteredItems(items: SearchableDropdownItem[], search: string) {
  const normalizedSearch = search.trim().toLowerCase();
  return useMemo(() => {
    if (!normalizedSearch) {
      return items;
    }
    return items.filter(
      (item) =>
        item.label.toLowerCase().includes(normalizedSearch) ||
        item.supportingText?.toLowerCase().includes(normalizedSearch),
    );
  }, [items, normalizedSearch]);
}
