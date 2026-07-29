import { Edit01, Plus, Settings01, Trash01, XClose } from "@untitledui/icons";
import { useState } from "react";
import {
  createDashboardChartPreset,
  type DashboardChartFilterMode,
  type DashboardChartPreset,
  deleteDashboardChartPreset,
  type SaveDashboardChartPreset,
  setActiveDashboardChartPreset,
  updateDashboardChartPreset,
} from "@/api/dashboardChartPresets";
import { fetchExpenseCategories } from "@/api/expenseCategories";
import { fetchIncomeCategories } from "@/api/incomeCategories";
import { fetchTrackingAccounts } from "@/api/trackingAccounts";
import type {
  TransactionTimeSeriesGranularity,
  TransactionType,
} from "@/api/transactions";
import { ConfirmationDialog } from "@/components/ConfirmationDialog";
import {
  MultiSelectFilter,
  type MultiSelectFilterOption,
} from "@/components/MultiSelectFilter";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { LoadingIndicator } from "@/components/untitled/application/loading-indicator/loading-indicator";
import {
  Dialog,
  Modal,
  ModalOverlay,
} from "@/components/untitled/application/modals/modal";
import { Button } from "@/components/untitled/base/buttons/button";
import { Dropdown } from "@/components/untitled/base/dropdown/dropdown";
import { Input } from "@/components/untitled/base/input/input";
import { Select } from "@/components/untitled/base/select/select";

type DashboardChartPresetControlProps = {
  transactionType: TransactionType;
  presets: DashboardChartPreset[];
  onPresetsChange: (presets: DashboardChartPreset[]) => void;
};

type PresetFormValue = SaveDashboardChartPreset;

const filterModeItems = [
  { id: "INCLUDE", label: "Include only" },
  { id: "EXCLUDE", label: "Exclude" },
];

const granularityItems = [
  { id: "AUTO", label: "Automatic" },
  { id: "DAY", label: "Daily" },
  { id: "WEEK", label: "Weekly" },
  { id: "MONTH", label: "Monthly" },
];

const emptyFormValue: PresetFormValue = {
  name: "",
  categoryFilterMode: "INCLUDE",
  categoryIds: [],
  accountFilterMode: "INCLUDE",
  accountIds: [],
  granularity: "AUTO",
};

export function DashboardChartPresetControl({
  transactionType,
  presets,
  onPresetsChange,
}: DashboardChartPresetControlProps) {
  const typeLabel = transactionType === "EXPENSE" ? "expenses" : "income";
  const activePreset = presets.find((preset) => preset.isActive);
  const [isEditorOpen, setIsEditorOpen] = useState(false);
  const [editingPreset, setEditingPreset] = useState<
    DashboardChartPreset | undefined
  >();
  const [formValue, setFormValue] = useState(emptyFormValue);
  const [accounts, setAccounts] = useState<MultiSelectFilterOption[]>();
  const [categories, setCategories] = useState<MultiSelectFilterOption[]>();
  const [isLoadingOptions, setIsLoadingOptions] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [isChangingActive, setIsChangingActive] = useState(false);
  const [presetToDelete, setPresetToDelete] = useState<
    DashboardChartPreset | undefined
  >();
  const [isDeleting, setIsDeleting] = useState(false);
  const [error, setError] = useState<string>();
  const [nameError, setNameError] = useState<string>();

  async function changeActive(presetId: number | null) {
    setIsChangingActive(true);
    setError(undefined);
    try {
      await setActiveDashboardChartPreset(transactionType, presetId);
      onPresetsChange(
        presets.map((preset) => ({
          ...preset,
          isActive: preset.id === presetId,
        })),
      );
    } catch {
      setError(`The ${typeLabel} chart view could not be changed.`);
    } finally {
      setIsChangingActive(false);
    }
  }

  async function openEditor(preset?: DashboardChartPreset) {
    const selectedAccountIds = preset?.accountIds ?? [];
    const selectedCategoryIds = preset?.categoryIds ?? [];
    setEditingPreset(preset);
    setFormValue(
      preset
        ? {
            name: preset.name,
            categoryFilterMode: preset.categoryFilterMode,
            categoryIds: preset.categoryIds,
            accountFilterMode: preset.accountFilterMode,
            accountIds: preset.accountIds,
            granularity: preset.granularity,
          }
        : emptyFormValue,
    );
    setNameError(undefined);
    setError(undefined);
    setIsEditorOpen(true);
    if (accounts && categories) {
      return;
    }
    setIsLoadingOptions(true);
    try {
      const [loadedAccounts, loadedCategories] = await Promise.all([
        fetchTrackingAccounts({ includeArchived: true }),
        transactionType === "EXPENSE"
          ? fetchExpenseCategories({ includeArchived: true })
          : fetchIncomeCategories({ includeArchived: true }),
      ]);
      setAccounts(
        loadedAccounts
          .filter(
            (account) =>
              !account.archived || selectedAccountIds.includes(account.id),
          )
          .map((account) => ({
            id: account.id,
            name: `${account.name}${account.archived ? " (Archived)" : ""}`,
          })),
      );
      setCategories(
        loadedCategories
          .filter(
            (category) =>
              !category.archived || selectedCategoryIds.includes(category.id),
          )
          .map((category) => ({
            id: category.id,
            name: `${category.name}${category.archived ? " (Archived)" : ""}`,
          })),
      );
    } catch {
      setError("Preset options could not be loaded. Try again in a moment.");
    } finally {
      setIsLoadingOptions(false);
    }
  }

  async function savePreset() {
    const name = formValue.name.trim();
    if (!name) {
      setNameError("Enter a preset name.");
      return;
    }
    setNameError(undefined);
    setError(undefined);
    setIsSaving(true);
    try {
      if (editingPreset) {
        const updated = await updateDashboardChartPreset(
          transactionType,
          editingPreset.id,
          { ...formValue, name },
        );
        onPresetsChange(
          presets.map((preset) =>
            preset.id === updated.id ? updated : preset,
          ),
        );
      } else {
        const created = await createDashboardChartPreset(transactionType, {
          ...formValue,
          name,
        });
        onPresetsChange([
          ...presets.map((preset) => ({ ...preset, isActive: false })),
          created,
        ]);
      }
      setIsEditorOpen(false);
    } catch {
      setError(
        "The preset could not be saved. Check the fields and try again.",
      );
    } finally {
      setIsSaving(false);
    }
  }

  async function deletePreset() {
    if (!presetToDelete) {
      return;
    }
    setIsDeleting(true);
    try {
      await deleteDashboardChartPreset(transactionType, presetToDelete.id);
      onPresetsChange(
        presets.filter((preset) => preset.id !== presetToDelete.id),
      );
      setPresetToDelete(undefined);
    } catch {
      setError("The preset could not be deleted. Try again in a moment.");
    } finally {
      setIsDeleting(false);
    }
  }

  return (
    <>
      <Dropdown.Root>
        <Button
          aria-label={`Configure ${typeLabel} chart`}
          color="tertiary"
          size="sm"
          iconLeading={Settings01}
          isDisabled={isChangingActive}
        />
        <Dropdown.Popover placement="bottom right" className="w-64">
          <Dropdown.Menu
            selectionMode="none"
            aria-label={`${typeLabel} chart presets`}
          >
            <Dropdown.Item
              label={`All ${typeLabel}`}
              addon={!activePreset ? "Current" : undefined}
              selectionIndicator="none"
              onAction={() => changeActive(null)}
            />
            {presets.map((preset) => (
              <Dropdown.Item
                key={preset.id}
                label={preset.name}
                addon={preset.isActive ? "Current" : undefined}
                selectionIndicator="none"
                onAction={() => changeActive(preset.id)}
              />
            ))}
            <Dropdown.Separator />
            <Dropdown.Item
              label="Create preset"
              icon={Plus}
              selectionIndicator="none"
              onAction={() => openEditor()}
            />
            {activePreset && (
              <Dropdown.Item
                label="Edit current preset"
                icon={Edit01}
                selectionIndicator="none"
                onAction={() => openEditor(activePreset)}
              />
            )}
            {activePreset && (
              <Dropdown.Item
                label="Delete current preset"
                icon={Trash01}
                selectionIndicator="none"
                onAction={() => setPresetToDelete(activePreset)}
              />
            )}
          </Dropdown.Menu>
        </Dropdown.Popover>
      </Dropdown.Root>

      <ModalOverlay
        isOpen={isEditorOpen}
        isDismissable
        className="dashboard-preset-modal-overlay"
        onOpenChange={setIsEditorOpen}
      >
        <Modal className="dashboard-preset-modal w-full max-w-2xl">
          <Dialog
            aria-label={`${editingPreset ? "Edit" : "Create"} ${typeLabel} chart preset`}
            className="dashboard-preset-dialog"
          >
            <div className="dashboard-preset-modal-header">
              <div>
                <h2>{editingPreset ? "Edit preset" : "Create preset"}</h2>
                <p>Choose which {typeLabel} appear in this chart.</p>
              </div>
              <Button
                aria-label="Close preset editor"
                color="tertiary"
                size="sm"
                iconLeading={XClose}
                onPress={() => setIsEditorOpen(false)}
              />
            </div>

            <div className="dashboard-preset-form">
              {error && <Alert tone="error" title={error} />}
              {isLoadingOptions ? (
                <div
                  className="dashboard-preset-loading"
                  role="status"
                  aria-label="Loading preset options"
                >
                  <LoadingIndicator size="sm" />
                </div>
              ) : (
                <>
                  <Input
                    label="Preset name"
                    name="presetName"
                    size="md"
                    value={formValue.name}
                    onChange={(name) => {
                      setFormValue({ ...formValue, name });
                      setNameError(undefined);
                    }}
                    isInvalid={Boolean(nameError)}
                    hint={nameError}
                    isRequired
                  />
                  <div className="dashboard-preset-filter-grid">
                    <Select
                      label="Category filter"
                      size="md"
                      items={filterModeItems}
                      selectedKey={formValue.categoryFilterMode}
                      onSelectionChange={(key) =>
                        setFormValue({
                          ...formValue,
                          categoryFilterMode: key as DashboardChartFilterMode,
                        })
                      }
                    >
                      {(item) => (
                        <Select.Item id={item.id}>{item.label}</Select.Item>
                      )}
                    </Select>
                    <MultiSelectFilter
                      label="Categories"
                      allLabel={
                        formValue.categoryFilterMode === "INCLUDE"
                          ? "All categories"
                          : "No categories excluded"
                      }
                      options={categories ?? []}
                      selectedIds={formValue.categoryIds}
                      emptySelectionMeansAll={
                        formValue.categoryFilterMode === "INCLUDE"
                      }
                      showSelectedTags={false}
                      onChange={(categoryIds) =>
                        setFormValue({ ...formValue, categoryIds })
                      }
                    />
                    <Select
                      label="Account filter"
                      size="md"
                      items={filterModeItems}
                      selectedKey={formValue.accountFilterMode}
                      onSelectionChange={(key) =>
                        setFormValue({
                          ...formValue,
                          accountFilterMode: key as DashboardChartFilterMode,
                        })
                      }
                    >
                      {(item) => (
                        <Select.Item id={item.id}>{item.label}</Select.Item>
                      )}
                    </Select>
                    <MultiSelectFilter
                      label="Accounts"
                      allLabel={
                        formValue.accountFilterMode === "INCLUDE"
                          ? "All accounts"
                          : "No accounts excluded"
                      }
                      options={accounts ?? []}
                      selectedIds={formValue.accountIds}
                      emptySelectionMeansAll={
                        formValue.accountFilterMode === "INCLUDE"
                      }
                      showSelectedTags={false}
                      onChange={(accountIds) =>
                        setFormValue({ ...formValue, accountIds })
                      }
                    />
                    <Select
                      label="Grouping"
                      size="md"
                      items={granularityItems}
                      selectedKey={formValue.granularity}
                      onSelectionChange={(key) =>
                        setFormValue({
                          ...formValue,
                          granularity: key as TransactionTimeSeriesGranularity,
                        })
                      }
                    >
                      {(item) => (
                        <Select.Item id={item.id}>{item.label}</Select.Item>
                      )}
                    </Select>
                  </div>
                </>
              )}
            </div>

            <div className="dashboard-preset-modal-actions">
              <Button
                color="tertiary"
                size="sm"
                onPress={() => setIsEditorOpen(false)}
                isDisabled={isSaving}
              >
                Cancel
              </Button>
              <Button
                color="primary"
                size="sm"
                onPress={savePreset}
                isLoading={isSaving}
                isDisabled={isLoadingOptions || !accounts || !categories}
              >
                {editingPreset ? "Save changes" : "Create preset"}
              </Button>
            </div>
          </Dialog>
        </Modal>
      </ModalOverlay>

      <ConfirmationDialog
        isOpen={Boolean(presetToDelete)}
        title={`Delete “${presetToDelete?.name ?? ""}”?`}
        description={`This ${typeLabel} chart preset will be permanently removed. The chart will return to the unfiltered view if this preset is active.`}
        confirmLabel="Delete preset"
        isConfirming={isDeleting}
        onCancel={() => setPresetToDelete(undefined)}
        onConfirm={deletePreset}
      />

      {error && !isEditorOpen && (
        <span className="sr-only" role="alert">
          {error}
        </span>
      )}
    </>
  );
}
