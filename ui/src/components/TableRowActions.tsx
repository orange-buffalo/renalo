import { Archive, Edit02, Eye, GitMerge, Trash01 } from "@untitledui/icons";
import type { ComponentProps, ReactNode } from "react";
import { Table } from "@/components/untitled/application/table/table";
import { Button } from "@/components/untitled/base/buttons/button";

type TableRowActionsProps = {
  children: ReactNode;
};

type TableEditActionProps = {
  label: string;
  onPress: () => void;
};

type TableDeleteActionProps = {
  label: string;
  onPress: () => void;
  isLoading?: boolean;
  isDisabled?: boolean;
  actionIcon?: string;
};

type TableMobileDetailsActionProps = {
  label: string;
};

type TableViewActionProps = {
  label: string;
  onPress: () => void;
};

type TableMergeActionProps = {
  label: string;
  onPress: () => void;
};

type TableArchiveActionProps = {
  label: string;
  onPress: () => void;
  isLoading?: boolean;
};

export function TableRowActions({ children }: TableRowActionsProps) {
  return <div className="table-row-actions">{children}</div>;
}

export function TableMobileDetailsAction({
  label,
}: TableMobileDetailsActionProps) {
  return <Table.MobileDetailsButton label={label} />;
}

export function TableEditAction({ label, onPress }: TableEditActionProps) {
  return (
    <Button
      aria-label={label}
      color="tertiary"
      size="sm"
      iconLeading={EditActionIcon}
      onPress={onPress}
    />
  );
}

export function TableViewAction({ label, onPress }: TableViewActionProps) {
  return (
    <Button
      aria-label={label}
      color="tertiary"
      size="sm"
      iconLeading={ViewActionIcon}
      onPress={onPress}
    />
  );
}

export function TableMergeAction({ label, onPress }: TableMergeActionProps) {
  return (
    <Button
      aria-label={label}
      color="tertiary"
      size="sm"
      iconLeading={MergeActionIcon}
      onPress={onPress}
    />
  );
}

export function TableArchiveAction({
  label,
  onPress,
  isLoading,
}: TableArchiveActionProps) {
  return (
    <Button
      aria-label={label}
      color="tertiary"
      size="sm"
      iconLeading={ArchiveActionIcon}
      isLoading={isLoading}
      onPress={onPress}
    />
  );
}

export function TableDeleteAction({
  label,
  onPress,
  isLoading,
  isDisabled,
  actionIcon = "delete",
}: TableDeleteActionProps) {
  function DeleteActionIcon(props: ComponentProps<typeof Trash01>) {
    return (
      <Trash01 {...props} data-action-icon={actionIcon} aria-hidden="true" />
    );
  }

  return (
    <Button
      aria-label={label}
      color="tertiary-destructive"
      size="sm"
      iconLeading={DeleteActionIcon}
      isLoading={isLoading}
      isDisabled={isDisabled}
      onPress={onPress}
    />
  );
}

function EditActionIcon(props: ComponentProps<typeof Edit02>) {
  return <Edit02 {...props} data-action-icon="edit" aria-hidden="true" />;
}

function ViewActionIcon(props: ComponentProps<typeof Eye>) {
  return <Eye {...props} data-action-icon="view" aria-hidden="true" />;
}

function MergeActionIcon(props: ComponentProps<typeof GitMerge>) {
  return <GitMerge {...props} data-action-icon="merge" aria-hidden="true" />;
}

function ArchiveActionIcon(props: ComponentProps<typeof Archive>) {
  return <Archive {...props} data-action-icon="archive" aria-hidden="true" />;
}
