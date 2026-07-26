import { ChevronDown, SearchLg } from "@untitledui/icons";
import { useEffect, useRef, useState } from "react";
import {
  Button as AriaButton,
  Autocomplete,
  GridList,
  GridListItem,
  type Selection,
} from "react-aria-components";
import { CheckboxBase } from "@/components/untitled/base/checkbox/checkbox";
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
  const [activeKey, setActiveKey] = useState<string>();
  const searchInputRef = useDropdownSearchFocus(isOpen);
  const selectedItem = items.find((item) => item.id === selectedKey);
  const visibleItems = items.filter((item) =>
    filterDropdownItem(getItemTextValue(item), search),
  );
  const effectiveActiveKey = visibleItems.some((item) => item.id === activeKey)
    ? activeKey
    : visibleItems[0]?.id;

  function moveActiveItem(direction: 1 | -1) {
    if (visibleItems.length === 0) {
      return;
    }
    const currentIndex = visibleItems.findIndex(
      (item) => item.id === effectiveActiveKey,
    );
    const nextIndex =
      (currentIndex + direction + visibleItems.length) % visibleItems.length;
    setActiveKey(visibleItems[nextIndex].id);
  }

  return (
    <div className={className ?? "searchable-dropdown-field"}>
      <Label isRequired={isRequired}>{label}</Label>
      <Dropdown.Root
        isOpen={isOpen}
        onOpenChange={(open) => {
          setIsOpen(open);
          if (!open) {
            setSearch("");
            setActiveKey(undefined);
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
            onInputChange={(value) => {
              setSearch(value);
              setActiveKey(undefined);
            }}
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
                onKeyDown={(event) => {
                  if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                    moveActiveItem(event.key === "ArrowDown" ? 1 : -1);
                  }
                }}
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
                  data-active={item.id === effectiveActiveKey || undefined}
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
  allLabel: string;
  items: SearchableDropdownItem[];
  selectedKeys: string[];
  searchPlaceholder?: string;
  onSelectionChange: (keys: string[]) => void;
};

export function SearchableMultiDropdown({
  label,
  allLabel,
  items,
  selectedKeys,
  searchPlaceholder = "Search",
  onSelectionChange,
}: SearchableMultiDropdownProps) {
  const [isOpen, setIsOpen] = useState(false);
  const [search, setSearch] = useState("");
  const [activeKey, setActiveKey] = useState<string>();
  const [rowDomIds, setRowDomIds] = useState<Record<string, string>>({});
  const searchInputRef = useDropdownSearchFocus(isOpen);
  const allItemKeys = items.map((item) => item.id);
  const visibleItems = items.filter((item) =>
    filterDropdownItem(getItemTextValue(item), search),
  );
  const effectiveActiveKey = visibleItems.some((item) => item.id === activeKey)
    ? activeKey
    : visibleItems[0]?.id;
  const effectiveSelectedKeys =
    selectedKeys.length === 0 ? allItemKeys : selectedKeys;

  function emitNormalizedSelection(keys: string[]) {
    const keySet = new Set(keys);
    const hasEveryItem =
      items.length > 0 && items.every((item) => keySet.has(item.id));
    onSelectionChange(hasEveryItem ? [] : keys);
  }

  function handleSelectionChange(selection: Selection) {
    if (selection === "all") {
      onSelectionChange([]);
      return;
    }
    emitNormalizedSelection(Array.from(selection).map(String));
  }

  function moveActiveItem(direction: 1 | -1) {
    if (visibleItems.length === 0) {
      return;
    }
    const currentIndex = visibleItems.findIndex(
      (item) => item.id === effectiveActiveKey,
    );
    const nextIndex =
      (currentIndex + direction + visibleItems.length) % visibleItems.length;
    setActiveKey(visibleItems[nextIndex].id);
  }

  function toggleActiveItem() {
    if (!effectiveActiveKey) {
      return;
    }
    const selectedKeySet = new Set(effectiveSelectedKeys);
    if (selectedKeySet.has(effectiveActiveKey)) {
      selectedKeySet.delete(effectiveActiveKey);
    } else {
      selectedKeySet.add(effectiveActiveKey);
    }
    emitNormalizedSelection(Array.from(selectedKeySet));
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
            setActiveKey(undefined);
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
                : allLabel}
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
            onKeyDownCapture={(event) => {
              if (event.key === "Escape") {
                event.preventDefault();
                setIsOpen(false);
              }
            }}
          >
            <Autocomplete
              inputValue={search}
              onInputChange={setSearch}
              filter={filterDropdownItem}
            >
              <div className="searchable-dropdown-search-wrap">
                <Input
                  ref={searchInputRef}
                  aria-label={`Search ${label.toLowerCase()}`}
                  aria-activedescendant={
                    effectiveActiveKey
                      ? rowDomIds[effectiveActiveKey]
                      : undefined
                  }
                  size="sm"
                  placeholder={searchPlaceholder}
                  icon={SearchLg}
                  onKeyDown={(event) => {
                    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
                      event.preventDefault();
                      event.stopPropagation();
                      moveActiveItem(event.key === "ArrowDown" ? 1 : -1);
                    } else if (event.key === "Enter") {
                      event.preventDefault();
                      event.stopPropagation();
                      toggleActiveItem();
                    }
                  }}
                />
              </div>
              <GridList
                aria-label={label}
                selectionMode="multiple"
                selectionBehavior="toggle"
                selectedKeys={new Set(effectiveSelectedKeys)}
                onSelectionChange={handleSelectionChange}
                className="searchable-dropdown-menu searchable-multi-dropdown-list"
                renderEmptyState={() => (
                  <p className="searchable-dropdown-empty">No matches</p>
                )}
              >
                {items.map((item) => (
                  <GridListItem
                    key={item.id}
                    id={item.id}
                    ref={(element) => {
                      if (element && rowDomIds[item.id] !== element.id) {
                        setRowDomIds((current) => ({
                          ...current,
                          [item.id]: element.id,
                        }));
                      }
                    }}
                    data-active={item.id === effectiveActiveKey || undefined}
                    textValue={getItemTextValue(item)}
                    className="searchable-multi-dropdown-row"
                  >
                    {({ isSelected }) => (
                      <>
                        <CheckboxBase isSelected={isSelected} />
                        <span className="searchable-dropdown-option">
                          <span>{item.label}</span>
                          {item.supportingText && (
                            <span>{item.supportingText}</span>
                          )}
                        </span>
                        <span className="searchable-multi-dropdown-actions">
                          <AriaButton
                            aria-label={`Only ${item.label}`}
                            className="searchable-multi-dropdown-action"
                            preventFocusOnPress
                            onPress={() => emitNormalizedSelection([item.id])}
                          >
                            Only
                          </AriaButton>
                        </span>
                      </>
                    )}
                  </GridListItem>
                ))}
              </GridList>
            </Autocomplete>
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

function getItemTextValue(item: SearchableDropdownItem) {
  return [item.label, item.supportingText].filter(Boolean).join(" ");
}

function filterDropdownItem(textValue: string, inputValue: string) {
  return textValue.toLowerCase().includes(inputValue.trim().toLowerCase());
}
