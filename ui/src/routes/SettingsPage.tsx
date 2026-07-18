import { Plus } from "@untitledui/icons";
import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router";
import { ApiError } from "@/api/client";
import {
  archiveExpenseCategory,
  type ExpenseCategory,
  fetchExpenseCategories,
  unarchiveExpenseCategory,
} from "@/api/expenseCategories";
import { importToshlCsv, type ToshlImportResult } from "@/api/imports";
import {
  archiveIncomeCategory,
  fetchIncomeCategories,
  type IncomeCategory,
  unarchiveIncomeCategory,
} from "@/api/incomeCategories";
import {
  archiveTrackingAccount,
  fetchTrackingAccounts,
  type TrackingAccount,
  unarchiveTrackingAccount,
} from "@/api/trackingAccounts";
import { ConfirmationDialog } from "@/components/ConfirmationDialog";
import { PageLayout } from "@/components/PageLayout";
import { TableEmptyState } from "@/components/TableEmptyState";
import { TableLoadingState } from "@/components/TableLoadingState";
import {
  TableAdjustAction,
  TableArchiveAction,
  TableEditAction,
  TableMergeAction,
  TableRowActions,
} from "@/components/TableRowActions";
import { Alert } from "@/components/untitled/application/alerts/alert";
import {
  Table,
  TableCard,
} from "@/components/untitled/application/table/table";
import { Tabs } from "@/components/untitled/application/tabs/tabs";
import { BadgeWithDot } from "@/components/untitled/base/badges/badges";
import { Button } from "@/components/untitled/base/buttons/button";
import { InputFile } from "@/components/untitled/base/input/input-file";
import { formatMoney } from "@/utils/money";

export function SettingsPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedTab = searchParams.get("tab");
  const selectedTab =
    requestedTab === "expense-categories" ||
    requestedTab === "income-categories" ||
    requestedTab === "import"
      ? requestedTab
      : "accounts";
  const [accounts, setAccounts] = useState<TrackingAccount[]>();
  const [expenseCategories, setExpenseCategories] =
    useState<ExpenseCategory[]>();
  const [incomeCategories, setIncomeCategories] = useState<IncomeCategory[]>();
  const [toshlFile, setToshlFile] = useState<File>();
  const [toshlResult, setToshlResult] = useState<ToshlImportResult>();
  const [accountsError, setAccountsError] = useState<string>();
  const [expenseCategoriesError, setExpenseCategoriesError] =
    useState<string>();
  const [incomeCategoriesError, setIncomeCategoriesError] = useState<string>();
  const [toshlImportError, setToshlImportError] =
    useState<ToshlImportFailure>();
  const [isImportingToshl, setIsImportingToshl] = useState(false);
  const [confirmingArchiveAccount, setConfirmingArchiveAccount] =
    useState<TrackingAccount>();
  const [confirmingArchiveCategory, setConfirmingArchiveCategory] = useState<
    | { kind: "expense"; category: ExpenseCategory }
    | { kind: "income"; category: IncomeCategory }
  >();
  const [updatingArchiveAccountId, setUpdatingArchiveAccountId] =
    useState<number>();
  const [updatingArchiveCategory, setUpdatingArchiveCategory] = useState<
    { kind: "expense" | "income"; id: number } | undefined
  >();

  const handleToshlImport = async () => {
    if (!toshlFile) {
      setToshlImportError({
        summary: "Choose the CSV export from Toshl before importing.",
      });
      return;
    }

    setIsImportingToshl(true);
    setToshlImportError(undefined);
    setToshlResult(undefined);
    try {
      setToshlResult(await importToshlCsv(await toshlFile.text()));
      setToshlFile(undefined);
    } catch (caughtError) {
      setToshlImportError(toToshlImportFailure(caughtError));
    } finally {
      setIsImportingToshl(false);
    }
  };

  const handleDownloadToshlReport = () => {
    if (!toshlResult) {
      return;
    }
    const reportCsv = buildToshlReportCsv(toshlResult);
    const reportUrl = URL.createObjectURL(
      new Blob([reportCsv], { type: "text/csv;charset=utf-8" }),
    );
    const link = document.createElement("a");
    link.href = reportUrl;
    link.download = "toshl-import-processing-report.csv";
    link.click();
    URL.revokeObjectURL(reportUrl);
  };

  const handleArchiveAccountConfirmed = async () => {
    if (!confirmingArchiveAccount) {
      return;
    }

    setUpdatingArchiveAccountId(confirmingArchiveAccount.id);
    setAccountsError(undefined);
    try {
      const archivedAccount = await archiveTrackingAccount(
        confirmingArchiveAccount.id,
      );
      setAccounts((currentAccounts) =>
        currentAccounts?.map((account) =>
          account.id === archivedAccount.id ? archivedAccount : account,
        ),
      );
      setConfirmingArchiveAccount(undefined);
    } catch {
      setAccountsError("Account could not be archived. Try again in a moment.");
    } finally {
      setUpdatingArchiveAccountId(undefined);
    }
  };

  const handleUnarchiveAccount = async (account: TrackingAccount) => {
    setUpdatingArchiveAccountId(account.id);
    setAccountsError(undefined);
    try {
      const unarchivedAccount = await unarchiveTrackingAccount(account.id);
      setAccounts((currentAccounts) =>
        currentAccounts?.map((currentAccount) =>
          currentAccount.id === unarchivedAccount.id
            ? unarchivedAccount
            : currentAccount,
        ),
      );
    } catch {
      setAccountsError(
        "Account could not be unarchived. Try again in a moment.",
      );
    } finally {
      setUpdatingArchiveAccountId(undefined);
    }
  };

  const handleArchiveCategoryConfirmed = async () => {
    if (!confirmingArchiveCategory) {
      return;
    }

    setUpdatingArchiveCategory({
      kind: confirmingArchiveCategory.kind,
      id: confirmingArchiveCategory.category.id,
    });
    if (confirmingArchiveCategory.kind === "expense") {
      setExpenseCategoriesError(undefined);
      try {
        const archivedCategory = await archiveExpenseCategory(
          confirmingArchiveCategory.category.id,
        );
        setExpenseCategories((currentCategories) =>
          currentCategories?.map((category) =>
            category.id === archivedCategory.id ? archivedCategory : category,
          ),
        );
        setConfirmingArchiveCategory(undefined);
      } catch {
        setExpenseCategoriesError(
          "Expense category could not be archived. Try again in a moment.",
        );
      } finally {
        setUpdatingArchiveCategory(undefined);
      }
    } else {
      setIncomeCategoriesError(undefined);
      try {
        const archivedCategory = await archiveIncomeCategory(
          confirmingArchiveCategory.category.id,
        );
        setIncomeCategories((currentCategories) =>
          currentCategories?.map((category) =>
            category.id === archivedCategory.id ? archivedCategory : category,
          ),
        );
        setConfirmingArchiveCategory(undefined);
      } catch {
        setIncomeCategoriesError(
          "Income category could not be archived. Try again in a moment.",
        );
      } finally {
        setUpdatingArchiveCategory(undefined);
      }
    }
  };

  const handleUnarchiveExpenseCategory = async (category: ExpenseCategory) => {
    setUpdatingArchiveCategory({ kind: "expense", id: category.id });
    setExpenseCategoriesError(undefined);
    try {
      const unarchivedCategory = await unarchiveExpenseCategory(category.id);
      setExpenseCategories((currentCategories) =>
        currentCategories?.map((currentCategory) =>
          currentCategory.id === unarchivedCategory.id
            ? unarchivedCategory
            : currentCategory,
        ),
      );
    } catch {
      setExpenseCategoriesError(
        "Expense category could not be unarchived. Try again in a moment.",
      );
    } finally {
      setUpdatingArchiveCategory(undefined);
    }
  };

  const handleUnarchiveIncomeCategory = async (category: IncomeCategory) => {
    setUpdatingArchiveCategory({ kind: "income", id: category.id });
    setIncomeCategoriesError(undefined);
    try {
      const unarchivedCategory = await unarchiveIncomeCategory(category.id);
      setIncomeCategories((currentCategories) =>
        currentCategories?.map((currentCategory) =>
          currentCategory.id === unarchivedCategory.id
            ? unarchivedCategory
            : currentCategory,
        ),
      );
    } catch {
      setIncomeCategoriesError(
        "Income category could not be unarchived. Try again in a moment.",
      );
    } finally {
      setUpdatingArchiveCategory(undefined);
    }
  };

  useEffect(() => {
    let isActive = true;
    setAccountsError(undefined);
    setExpenseCategoriesError(undefined);
    setIncomeCategoriesError(undefined);

    fetchTrackingAccounts({ includeArchived: true })
      .then((nextAccounts) => {
        if (isActive) {
          setAccounts(nextAccounts);
        }
      })
      .catch(() => {
        if (isActive) {
          setAccountsError(
            "Accounts could not be loaded. Try again in a moment.",
          );
        }
      });
    fetchExpenseCategories({ includeArchived: true })
      .then((nextCategories) => {
        if (isActive) {
          setExpenseCategories(nextCategories);
        }
      })
      .catch(() => {
        if (isActive) {
          setExpenseCategoriesError(
            "Expense categories could not be loaded. Try again in a moment.",
          );
        }
      });
    fetchIncomeCategories({ includeArchived: true })
      .then((nextCategories) => {
        if (isActive) {
          setIncomeCategories(nextCategories);
        }
      })
      .catch(() => {
        if (isActive) {
          setIncomeCategoriesError(
            "Income categories could not be loaded. Try again in a moment.",
          );
        }
      });

    return () => {
      isActive = false;
    };
  }, []);

  return (
    <PageLayout
      title="Budget settings"
      description="Configure the budget workspace used for tracking and analytics."
    >
      <Tabs
        selectedKey={selectedTab}
        onSelectionChange={(key) => {
          if (key === "expense-categories") {
            setSearchParams({ tab: "expense-categories" }, { replace: true });
          } else if (key === "income-categories") {
            setSearchParams({ tab: "income-categories" }, { replace: true });
          } else if (key === "import") {
            setSearchParams({ tab: "import" }, { replace: true });
          } else {
            setSearchParams({}, { replace: true });
          }
        }}
        className="settings-tabs"
      >
        <Tabs.List
          size="md"
          type="button-brand"
          aria-label="Settings sections"
          className="settings-tabs-list"
        >
          <Tabs.Item id="accounts" className="settings-tab-item">
            Accounts
          </Tabs.Item>
          <Tabs.Item id="expense-categories" className="settings-tab-item">
            Expense Categories
          </Tabs.Item>
          <Tabs.Item id="income-categories" className="settings-tab-item">
            Income Categories
          </Tabs.Item>
          <Tabs.Item id="import" className="settings-tab-item">
            Import
          </Tabs.Item>
        </Tabs.List>
        <Tabs.Panel id="accounts" className="settings-tab-panel">
          <div className="settings-tab-actions">
            <Button
              color="secondary"
              size="sm"
              iconLeading={Plus}
              onPress={() => navigate("/settings/accounts/create")}
            >
              Add new account
            </Button>
          </div>
          <TableCard.Root size="sm">
            {accountsError && (
              <p
                className="user-management-message user-management-error"
                role="alert"
              >
                {accountsError}
              </p>
            )}
            {!accounts ? (
              <TableLoadingState label="Loading accounts" />
            ) : accounts.length === 0 ? (
              <TableEmptyState title="No accounts found" />
            ) : (
              <Table aria-label="Tracking accounts" size="sm">
                <Table.Header>
                  <Table.Head id="name" label="Name" isRowHeader />
                  <Table.Head id="currency" label="Currency" />
                  <Table.Head id="initialBalance" label="Initial balance" />
                  <Table.Head id="default" label="Default" />
                  <Table.Head id="status" label="Status" />
                  <Table.Head
                    id="actions"
                    label="Actions"
                    mobileRole="actions"
                    className="[&>div]:justify-end"
                  />
                </Table.Header>
                <Table.Body>
                  {accounts.map((account) => (
                    <Table.Row
                      id={account.id}
                      key={account.id}
                      data-testid={`account-row-${account.id}`}
                    >
                      <Table.Cell>{account.name}</Table.Cell>
                      <Table.Cell>{account.currency}</Table.Cell>
                      <Table.Cell mobileLabel="Initial balance">
                        {formatMoney(
                          account.initialBalanceMinor,
                          account.currency,
                        )}
                      </Table.Cell>
                      <Table.Cell mobileLabel="Default">
                        <BadgeWithDot
                          color={account.isDefault ? "success" : "gray"}
                          size="sm"
                        >
                          {account.isDefault ? "Default" : "No"}
                        </BadgeWithDot>
                      </Table.Cell>
                      <Table.Cell mobileLabel="Status">
                        <BadgeWithDot
                          color={account.archived ? "gray" : "success"}
                          size="sm"
                        >
                          {account.archived ? "Archived" : "Active"}
                        </BadgeWithDot>
                      </Table.Cell>
                      <Table.Cell mobileRole="actions">
                        <TableRowActions>
                          <TableArchiveAction
                            label={
                              account.archived
                                ? `Unarchive ${account.name}`
                                : `Archive ${account.name}`
                            }
                            isLoading={updatingArchiveAccountId === account.id}
                            onPress={() => {
                              if (account.archived) {
                                handleUnarchiveAccount(account);
                              } else {
                                setConfirmingArchiveAccount(account);
                              }
                            }}
                          />
                          <TableMergeAction
                            label={`Merge ${account.name}`}
                            onPress={() =>
                              navigate(`/settings/accounts/${account.id}/merge`)
                            }
                          />
                          <TableAdjustAction
                            label={`Adjust ${account.name}`}
                            onPress={() =>
                              navigate(
                                `/settings/accounts/${account.id}/adjustments`,
                              )
                            }
                          />
                          <TableEditAction
                            label={`Edit ${account.name}`}
                            onPress={() =>
                              navigate(`/settings/accounts/${account.id}`)
                            }
                          />
                        </TableRowActions>
                      </Table.Cell>
                    </Table.Row>
                  ))}
                </Table.Body>
              </Table>
            )}
          </TableCard.Root>
        </Tabs.Panel>
        <Tabs.Panel id="expense-categories" className="settings-tab-panel">
          <div className="settings-tab-actions">
            <Button
              color="secondary"
              size="sm"
              iconLeading={Plus}
              onPress={() => navigate("/settings/expense-categories/create")}
            >
              Add new category
            </Button>
          </div>
          <TableCard.Root size="sm">
            {expenseCategoriesError && (
              <p
                className="user-management-message user-management-error"
                role="alert"
              >
                {expenseCategoriesError}
              </p>
            )}
            {!expenseCategories ? (
              <TableLoadingState label="Loading expense categories" />
            ) : expenseCategories.length === 0 ? (
              <TableEmptyState title="No expense categories found" />
            ) : (
              <Table aria-label="Expense categories" size="sm">
                <Table.Header>
                  <Table.Head id="name" label="Name" isRowHeader />
                  <Table.Head id="status" label="Status" />
                  <Table.Head
                    id="actions"
                    label="Actions"
                    mobileRole="actions"
                    className="[&>div]:justify-end"
                  />
                </Table.Header>
                <Table.Body>
                  {expenseCategories.map((category) => (
                    <Table.Row
                      id={category.id}
                      key={category.id}
                      data-testid={`expense-category-row-${category.id}`}
                    >
                      <Table.Cell>{category.name}</Table.Cell>
                      <Table.Cell mobileLabel="Status">
                        <BadgeWithDot
                          color={category.archived ? "gray" : "success"}
                          size="sm"
                        >
                          {category.archived ? "Archived" : "Active"}
                        </BadgeWithDot>
                      </Table.Cell>
                      <Table.Cell mobileRole="actions">
                        <TableRowActions>
                          <TableArchiveAction
                            label={
                              category.archived
                                ? `Unarchive ${category.name}`
                                : `Archive ${category.name}`
                            }
                            isLoading={
                              updatingArchiveCategory?.kind === "expense" &&
                              updatingArchiveCategory.id === category.id
                            }
                            onPress={() => {
                              if (category.archived) {
                                handleUnarchiveExpenseCategory(category);
                              } else {
                                setConfirmingArchiveCategory({
                                  kind: "expense",
                                  category,
                                });
                              }
                            }}
                          />
                          {hasCategoryMergeTarget(
                            category,
                            expenseCategories,
                          ) && (
                            <TableMergeAction
                              label={`Merge ${category.name}`}
                              onPress={() =>
                                navigate(
                                  `/settings/expense-categories/${category.id}/merge`,
                                )
                              }
                            />
                          )}
                          <TableEditAction
                            label={`Edit ${category.name}`}
                            onPress={() =>
                              navigate(
                                `/settings/expense-categories/${category.id}`,
                              )
                            }
                          />
                        </TableRowActions>
                      </Table.Cell>
                    </Table.Row>
                  ))}
                </Table.Body>
              </Table>
            )}
          </TableCard.Root>
        </Tabs.Panel>
        <Tabs.Panel id="income-categories" className="settings-tab-panel">
          <div className="settings-tab-actions">
            <Button
              color="secondary"
              size="sm"
              iconLeading={Plus}
              onPress={() => navigate("/settings/income-categories/create")}
            >
              Add new category
            </Button>
          </div>
          <TableCard.Root size="sm">
            {incomeCategoriesError && (
              <p
                className="user-management-message user-management-error"
                role="alert"
              >
                {incomeCategoriesError}
              </p>
            )}
            {!incomeCategories ? (
              <TableLoadingState label="Loading income categories" />
            ) : incomeCategories.length === 0 ? (
              <TableEmptyState title="No income categories found" />
            ) : (
              <Table aria-label="Income categories" size="sm">
                <Table.Header>
                  <Table.Head id="name" label="Name" isRowHeader />
                  <Table.Head id="status" label="Status" />
                  <Table.Head
                    id="actions"
                    label="Actions"
                    mobileRole="actions"
                    className="[&>div]:justify-end"
                  />
                </Table.Header>
                <Table.Body>
                  {incomeCategories.map((category) => (
                    <Table.Row
                      id={category.id}
                      key={category.id}
                      data-testid={`income-category-row-${category.id}`}
                    >
                      <Table.Cell>{category.name}</Table.Cell>
                      <Table.Cell mobileLabel="Status">
                        <BadgeWithDot
                          color={category.archived ? "gray" : "success"}
                          size="sm"
                        >
                          {category.archived ? "Archived" : "Active"}
                        </BadgeWithDot>
                      </Table.Cell>
                      <Table.Cell mobileRole="actions">
                        <TableRowActions>
                          <TableArchiveAction
                            label={
                              category.archived
                                ? `Unarchive ${category.name}`
                                : `Archive ${category.name}`
                            }
                            isLoading={
                              updatingArchiveCategory?.kind === "income" &&
                              updatingArchiveCategory.id === category.id
                            }
                            onPress={() => {
                              if (category.archived) {
                                handleUnarchiveIncomeCategory(category);
                              } else {
                                setConfirmingArchiveCategory({
                                  kind: "income",
                                  category,
                                });
                              }
                            }}
                          />
                          {hasCategoryMergeTarget(
                            category,
                            incomeCategories,
                          ) && (
                            <TableMergeAction
                              label={`Merge ${category.name}`}
                              onPress={() =>
                                navigate(
                                  `/settings/income-categories/${category.id}/merge`,
                                )
                              }
                            />
                          )}
                          <TableEditAction
                            label={`Edit ${category.name}`}
                            onPress={() =>
                              navigate(
                                `/settings/income-categories/${category.id}`,
                              )
                            }
                          />
                        </TableRowActions>
                      </Table.Cell>
                    </Table.Row>
                  ))}
                </Table.Body>
              </Table>
            )}
          </TableCard.Root>
        </Tabs.Panel>
        <Tabs.Panel id="import" className="settings-tab-panel">
          <section className="standard-page-panel profile-panel settings-import-panel">
            <div className="settings-import-heading">
              <h2>Toshl</h2>
              <p>
                Import expenses, income, accounts, and categories from a Toshl
                CSV export.
              </p>
            </div>

            <Alert
              tone="brand"
              title="Prepare your Toshl export"
              className="settings-import-instructions"
            >
              <ol>
                <li>Sign in to Toshl.</li>
                <li>
                  <a
                    href="https://toshl.com/app/#/export/export"
                    target="_blank"
                    rel="noreferrer"
                  >
                    Open Exports and reports
                  </a>
                  .
                </li>
                <li>
                  Select <strong>CSV</strong> as the export format.
                </li>
                <li>
                  Include only <strong>Expenses</strong> and{" "}
                  <strong>Income</strong>.
                </li>
                <li>
                  Set the time span to <strong>All time</strong>.
                </li>
                <li>
                  Click <strong>Generate</strong>, then download the CSV file.
                </li>
              </ol>
            </Alert>

            {toshlImportError && (
              <Alert tone="error" title="Import failed">
                <p>{toshlImportError.summary}</p>
                {toshlImportError.details && (
                  <p>
                    <strong>Details:</strong> {toshlImportError.details}
                  </p>
                )}
              </Alert>
            )}

            {toshlResult && (
              <Alert
                tone={toshlResult.warnings.length > 0 ? "warning" : "success"}
                title={
                  toshlResult.warnings.length > 0
                    ? "Import completed with warnings"
                    : "Import complete"
                }
              >
                <p>
                  Imported {toshlResult.importedExpenses} expenses and{" "}
                  {toshlResult.importedIncomes} income entries. Skipped{" "}
                  {toshlResult.skippedDuplicateExpenses} duplicate expenses and{" "}
                  {toshlResult.skippedDuplicateIncomes} duplicate income
                  entries. Imported {toshlResult.importedTransfers} transfers
                  and skipped {toshlResult.skippedDuplicateTransfers} duplicate
                  transfers. {formatUnmatchedTransferSummary(toshlResult)}
                </p>
                <Button
                  color="secondary"
                  size="sm"
                  onPress={handleDownloadToshlReport}
                >
                  Get processing report
                </Button>
              </Alert>
            )}

            <div className="settings-import-controls">
              <InputFile
                label="Toshl CSV file"
                hint="CSV files exported from Toshl"
                variant="dropzone"
                buttonText="Click to upload"
                acceptedFileTypes={[".csv", "text/csv"]}
                isRequired
                isDisabled={isImportingToshl}
                onChange={(files) => {
                  setToshlFile(files?.item(0) ?? undefined);
                  setToshlResult(undefined);
                  setToshlImportError(undefined);
                }}
              />
              <div className="settings-import-actions">
                <Button
                  color="primary"
                  size="sm"
                  isLoading={isImportingToshl}
                  isDisabled={!toshlFile || isImportingToshl}
                  onPress={handleToshlImport}
                >
                  Import
                </Button>
              </div>
            </div>
          </section>
        </Tabs.Panel>
      </Tabs>
      <ConfirmationDialog
        isOpen={Boolean(confirmingArchiveAccount)}
        title="Archive account?"
        description={
          confirmingArchiveAccount
            ? `${confirmingArchiveAccount.name} will be hidden from dashboards, transaction forms, and account filters. Existing transactions and transfers remain linked to it and you can unarchive the account later.`
            : ""
        }
        confirmLabel="Archive account"
        isConfirming={Boolean(updatingArchiveAccountId)}
        onCancel={() => setConfirmingArchiveAccount(undefined)}
        onConfirm={handleArchiveAccountConfirmed}
      />
      <ConfirmationDialog
        isOpen={Boolean(confirmingArchiveCategory)}
        title="Archive category?"
        description={
          confirmingArchiveCategory
            ? `${confirmingArchiveCategory.category.name} will be hidden from transaction forms and filters. Existing transactions remain linked to it and you can unarchive the category later.`
            : ""
        }
        confirmLabel="Archive category"
        isConfirming={Boolean(updatingArchiveCategory)}
        onCancel={() => setConfirmingArchiveCategory(undefined)}
        onConfirm={handleArchiveCategoryConfirmed}
      />
    </PageLayout>
  );
}

function hasCategoryMergeTarget<
  TCategory extends { id: number; archived: boolean },
>(category: TCategory, categories: TCategory[]) {
  return categories.some(
    (candidateCategory) =>
      candidateCategory.id !== category.id && !candidateCategory.archived,
  );
}

function buildToshlReportCsv(result: ToshlImportResult) {
  const rows = [
    [
      "Line number",
      "Date",
      "Account",
      "Category",
      "Type",
      "Amount",
      "Currency",
      "Status",
      "Reason",
    ],
    ...result.report.map((entry) => [
      entry.lineNumber.toString(),
      entry.date,
      entry.account,
      entry.category,
      entry.type.toLowerCase(),
      formatMoney(entry.amountMinor, entry.currency),
      entry.currency,
      entry.status,
      entry.reason,
    ]),
  ];
  return rows.map((row) => row.map(escapeCsvValue).join(",")).join("\n");
}

function formatUnmatchedTransferSummary(result: ToshlImportResult) {
  if (result.warnings.length === 1) {
    return "1 transfer row could not be matched.";
  }
  return `${result.warnings.length} transfer rows could not be matched.`;
}

type ToshlImportFailure = {
  summary: string;
  details?: string;
};

function toToshlImportFailure(caughtError: unknown): ToshlImportFailure {
  if (!(caughtError instanceof ApiError)) {
    return {
      summary:
        "Toshl CSV could not be imported. Check the file format and try again.",
    };
  }

  return {
    summary:
      caughtError.status >= 500
        ? "Toshl CSV could not be imported because the server encountered an error. Check the application logs."
        : "Toshl CSV could not be imported. Correct the reported problem and try again.",
    details:
      caughtError.details ??
      `HTTP ${caughtError.status}${caughtError.code ? ` (${caughtError.code})` : ""}`,
  };
}

function escapeCsvValue(value: string) {
  if (![",", '"', "\n", "\r"].some((char) => value.includes(char))) {
    return value;
  }
  return `"${value.replaceAll('"', '""')}"`;
}
