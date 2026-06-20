"use client";

import type { ComponentPropsWithRef, HTMLAttributes, MouseEvent, ReactNode, Ref, TdHTMLAttributes, ThHTMLAttributes } from "react";
import { createContext, isValidElement, useContext, useState } from "react";
import { ArrowDown, ChevronSelectorVertical, Copy01, Edit01, HelpCircle, InfoCircle, Trash01 } from "@untitledui/icons";
import type {
    CellProps as AriaCellProps,
    ColumnProps as AriaColumnProps,
    RowProps as AriaRowProps,
    TableHeaderProps as AriaTableHeaderProps,
    TableProps as AriaTableProps,
} from "react-aria-components";
import {
    Cell as AriaCell,
    Collection as AriaCollection,
    Column as AriaColumn,
    Group as AriaGroup,
    Row as AriaRow,
    Table as AriaTable,
    TableBody as AriaTableBody,
    TableHeader as AriaTableHeader,
    useTableOptions,
} from "react-aria-components";
import { Badge } from "@/components/untitled/base/badges/badges";
import { Checkbox } from "@/components/untitled/base/checkbox/checkbox";
import { Dropdown } from "@/components/untitled/base/dropdown/dropdown";
import { Tooltip, TooltipTrigger } from "@/components/untitled/base/tooltip/tooltip";
import { cx } from "@/utils/cx";

type MobileCellRole = "title" | "subtitle" | "detail" | "actions" | "hidden";

export const TableRowActionsDropdown = () => (
    <Dropdown.Root>
        <Dropdown.DotsButton />

        <Dropdown.Popover className="w-min">
            <Dropdown.Menu>
                <Dropdown.Item icon={Edit01}>
                    <span className="pr-4">Edit</span>
                </Dropdown.Item>
                <Dropdown.Item icon={Copy01}>
                    <span className="pr-4">Copy link</span>
                </Dropdown.Item>
                <Dropdown.Item icon={Trash01}>
                    <span className="pr-4">Delete</span>
                </Dropdown.Item>
            </Dropdown.Menu>
        </Dropdown.Popover>
    </Dropdown.Root>
);

const TableContext = createContext<{ size: "sm" | "md" }>({ size: "md" });

const TableCardRoot = ({ children, className, size = "md", ...props }: HTMLAttributes<HTMLDivElement> & { size?: "sm" | "md" }) => {
    return (
        <TableContext.Provider value={{ size }}>
            <div {...props} className={cx("table-card-root overflow-hidden rounded-xl bg-primary shadow-xs ring-1 ring-secondary", className)}>
                {children}
            </div>
        </TableContext.Provider>
    );
};

interface TableCardHeaderProps {
    /** The title of the table card header. */
    title: string;
    /** The badge displayed next to the title. */
    badge?: ReactNode;
    /** The description of the table card header. */
    description?: string;
    /** The content displayed after the title and badge. */
    contentTrailing?: ReactNode;
    /** The class name of the table card header. */
    className?: string;
}

const TableCardHeader = ({ title, badge, description, contentTrailing, className }: TableCardHeaderProps) => {
    const { size } = useContext(TableContext);

    return (
        <div
            className={cx(
                "relative flex flex-col items-start gap-4 border-b border-secondary bg-primary px-4 md:flex-row",
                size === "sm" ? "py-4 md:px-5" : "py-5 md:px-6",
                className,
            )}
        >
            <div className="flex flex-1 flex-col gap-0.5">
                <div className="flex items-center gap-2">
                    <h2 className="text-md font-semibold text-primary">{title}</h2>
                    {badge ? (
                        isValidElement(badge) ? (
                            badge
                        ) : (
                            <Badge color="gray" size="sm" type="modern">
                                {badge}
                            </Badge>
                        )
                    ) : null}
                </div>
                {description && <p className="text-sm text-tertiary">{description}</p>}
            </div>
            {contentTrailing}
        </div>
    );
};

interface TableRootProps extends AriaTableProps, Omit<ComponentPropsWithRef<"table">, "className" | "slot" | "style"> {
    size?: "sm" | "md";
}

const TableRoot = ({ className, size = "md", ...props }: TableRootProps) => {
    const context = useContext(TableContext);

    return (
        <TableContext.Provider value={{ size: context?.size ?? size }}>
            <div className="table-responsive-wrapper overflow-x-auto">
                <AriaTable className={(state) => cx("responsive-table w-full overflow-x-hidden", typeof className === "function" ? className(state) : className)} {...props} />
            </div>
        </TableContext.Provider>
    );
};
TableRoot.displayName = "Table";

interface TableHeaderProps<T extends object>
    extends AriaTableHeaderProps<T>, Omit<ComponentPropsWithRef<"thead">, "children" | "className" | "slot" | "style"> {
    bordered?: boolean;
    size?: "sm" | "md";
}

const TableHeader = <T extends object>({ columns, children, bordered = true, className, size: sizeProp, ...props }: TableHeaderProps<T>) => {
    const context = useContext(TableContext);
    const { selectionBehavior, selectionMode } = useTableOptions();

    const size = sizeProp ?? context.size;

    return (
        <AriaTableHeader
            {...props}
            className={(state) =>
                cx(
                    "relative bg-secondary",
                    size === "sm" ? "h-9" : "h-11",

                    // Row border—using an "after" pseudo-element to avoid the border taking up space.
                    bordered &&
                        "[&>tr>th]:after:pointer-events-none [&>tr>th]:after:absolute [&>tr>th]:after:inset-x-0 [&>tr>th]:after:bottom-0 [&>tr>th]:after:h-px [&>tr>th]:after:bg-border-secondary [&>tr>th]:focus-visible:after:bg-transparent",

                    typeof className === "function" ? className(state) : className,
                )
            }
        >
            {selectionBehavior === "toggle" && (
                <AriaColumn className={cx("relative py-2 pr-0 pl-4", size === "sm" ? "w-9 md:pl-5" : "w-11 md:pl-6")}>
                    {selectionMode === "multiple" && (
                        <div className="flex items-start">
                            <Checkbox slot="selection" size="md" />
                        </div>
                    )}
                </AriaColumn>
            )}
            <AriaCollection items={columns}>{children}</AriaCollection>
        </AriaTableHeader>
    );
};

TableHeader.displayName = "TableHeader";

interface TableHeadProps extends AriaColumnProps, Omit<ThHTMLAttributes<HTMLTableCellElement>, "children" | "className" | "style" | "id"> {
    label?: string;
    tooltip?: string;
    mobileRole?: MobileCellRole;
}

const TableHead = ({ className, tooltip, label, children, mobileRole, ...props }: TableHeadProps) => {
    const { selectionBehavior } = useTableOptions();

    return (
        <AriaColumn
            {...props}
            data-mobile-role={mobileRole}
            className={(state) =>
                cx(
                    "relative p-0 px-6 py-2 outline-hidden focus-visible:z-1 focus-visible:ring-2 focus-visible:ring-focus-ring focus-visible:ring-offset-bg-primary focus-visible:ring-inset",
                    selectionBehavior === "toggle" && "nth-2:pl-3",
                    state.allowsSorting && "cursor-pointer",
                    typeof className === "function" ? className(state) : className,
                )
            }
        >
            {(state) => (
                <AriaGroup className="flex items-center gap-1">
                    <div className="flex items-center gap-1">
                        {label && <span className="text-xs font-semibold whitespace-nowrap text-quaternary">{label}</span>}
                        {typeof children === "function" ? children(state) : children}
                    </div>

                    {tooltip && (
                        <Tooltip title={tooltip} placement="top">
                            <TooltipTrigger className="cursor-pointer text-fg-quaternary transition duration-100 ease-linear hover:text-fg-quaternary_hover focus:text-fg-quaternary_hover">
                                <HelpCircle className="size-4" />
                            </TooltipTrigger>
                        </Tooltip>
                    )}

                    {state.allowsSorting &&
                        (state.sortDirection ? (
                            <ArrowDown className={cx("size-3 stroke-[3px] text-fg-quaternary", state.sortDirection === "ascending" && "rotate-180")} />
                        ) : (
                            <ChevronSelectorVertical size={12} strokeWidth={3} className="text-fg-quaternary" />
                        ))}
                </AriaGroup>
            )}
        </AriaColumn>
    );
};
TableHead.displayName = "TableHead";

interface TableRowProps<T extends object>
    extends AriaRowProps<T>, Omit<ComponentPropsWithRef<"tr">, "children" | "className" | "onClick" | "slot" | "style" | "id"> {
    highlightSelectedRow?: boolean;
    size?: "sm" | "md";
}

const TableRow = <T extends object>({ columns, children, className, highlightSelectedRow = true, size: sizeProp, ...props }: TableRowProps<T>) => {
    const context = useContext(TableContext);
    const { selectionBehavior } = useTableOptions();

    const size = sizeProp ?? context.size;

    return (
        <AriaRow
            {...props}
            className={(state) =>
                cx(
                    "relative outline-focus-ring transition-colors after:pointer-events-none hover:bg-secondary focus-visible:outline-2 focus-visible:-outline-offset-2",
                    size === "sm" ? "h-14" : "h-18",
                    highlightSelectedRow && "selected:bg-secondary",

                    // Row border—using an "after" pseudo-element to avoid the border taking up space.
                    "[&>td]:after:absolute [&>td]:after:inset-x-0 [&>td]:after:bottom-0 [&>td]:after:h-px [&>td]:after:w-full [&>td]:after:bg-border-secondary last:[&>td]:after:hidden [&>td]:focus-visible:after:opacity-0 focus-visible:[&>td]:after:opacity-0",

                    typeof className === "function" ? className(state) : className,
                )
            }
        >
            {selectionBehavior === "toggle" && (
                <AriaCell className={cx("relative py-2 pr-0 pl-4", size === "sm" ? "md:pl-5" : "md:pl-6")}>
                    <div className="flex items-end">
                        <Checkbox slot="selection" size="md" />
                    </div>
                </AriaCell>
            )}
            <AriaCollection items={columns}>{children}</AriaCollection>
        </AriaRow>
    );
};

TableRow.displayName = "TableRow";

interface TableCellProps extends AriaCellProps, Omit<TdHTMLAttributes<HTMLTableCellElement>, "children" | "className" | "style" | "id"> {
    ref?: Ref<HTMLTableCellElement>;
    size?: "sm" | "md";
    mobileLabel?: string;
    mobileRole?: MobileCellRole;
}

const TableCell = ({ className, children, mobileLabel, mobileRole, size: sizeProp, ...props }: TableCellProps) => {
    const context = useContext(TableContext);
    const { selectionBehavior } = useTableOptions();

    const size = sizeProp ?? context.size;

    return (
        <AriaCell
            {...props}
            data-mobile-label={mobileLabel}
            data-mobile-role={mobileRole}
            className={(state) =>
                cx(
                    "relative text-sm text-tertiary outline-focus-ring focus-visible:z-1 focus-visible:outline-2 focus-visible:-outline-offset-2",
                    size === "sm" && "px-5 py-3",
                    size === "md" && "px-6 py-4",

                    selectionBehavior === "toggle" && "nth-2:pl-3",

                    typeof className === "function" ? className(state) : className,
                )
            }
        >
            {children}
        </AriaCell>
    );
};
TableCell.displayName = "TableCell";

interface TableMobileDetailsButtonProps {
    label?: string;
}

const TableMobileDetailsButton = ({ label = "Show details" }: TableMobileDetailsButtonProps) => {
    const [detailsExpanded, setDetailsExpanded] = useState(false);

    function toggleDetails(event: MouseEvent<HTMLButtonElement>) {
        const nextExpanded = !detailsExpanded;
        setDetailsExpanded(nextExpanded);
        const row = event.currentTarget.closest("tr, [role='row']");
        if (nextExpanded) {
            row?.setAttribute("data-mobile-details-expanded", "true");
        } else {
            row?.removeAttribute("data-mobile-details-expanded");
        }
    }

    return (
        <button
            type="button"
            className="mobile-table-details-button"
            aria-label={detailsExpanded ? "Hide details" : label}
            aria-expanded={detailsExpanded}
            onClick={toggleDetails}
        >
            <InfoCircle className="size-5" />
        </button>
    );
};
TableMobileDetailsButton.displayName = "TableMobileDetailsButton";

const TableCard = {
    Root: TableCardRoot,
    Header: TableCardHeader,
};

const Table = TableRoot as typeof TableRoot & {
    Body: typeof AriaTableBody;
    Cell: typeof TableCell;
    Head: typeof TableHead;
    Header: typeof TableHeader;
    MobileDetailsButton: typeof TableMobileDetailsButton;
    Row: typeof TableRow;
};
Table.Body = AriaTableBody;
Table.Cell = TableCell;
Table.Head = TableHead;
Table.Header = TableHeader;
Table.MobileDetailsButton = TableMobileDetailsButton;
Table.Row = TableRow;

export { Table, TableCard };
