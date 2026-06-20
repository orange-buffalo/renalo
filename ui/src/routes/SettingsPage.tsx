import { Edit02, Plus } from "@untitledui/icons";
import type { ComponentProps } from "react";
import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router";
import {
  type ExpenseCategory,
  fetchExpenseCategories,
} from "@/api/expenseCategories";
import {
  fetchIncomeCategories,
  type IncomeCategory,
} from "@/api/incomeCategories";
import {
  fetchTrackingAccounts,
  type TrackingAccount,
} from "@/api/trackingAccounts";
import { PageLayout } from "@/components/PageLayout";
import {
  Table,
  TableCard,
} from "@/components/untitled/application/table/table";
import { Tabs } from "@/components/untitled/application/tabs/tabs";
import { BadgeWithDot } from "@/components/untitled/base/badges/badges";
import { Button } from "@/components/untitled/base/buttons/button";
import { formatMoney } from "@/utils/money";

export function SettingsPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedTab = searchParams.get("tab");
  const selectedTab =
    requestedTab === "expense-categories" ||
    requestedTab === "income-categories"
      ? requestedTab
      : "accounts";
  const [accounts, setAccounts] = useState<TrackingAccount[]>();
  const [expenseCategories, setExpenseCategories] =
    useState<ExpenseCategory[]>();
  const [incomeCategories, setIncomeCategories] = useState<IncomeCategory[]>();
  const [accountsError, setAccountsError] = useState<string>();
  const [expenseCategoriesError, setExpenseCategoriesError] =
    useState<string>();
  const [incomeCategoriesError, setIncomeCategoriesError] = useState<string>();

  useEffect(() => {
    let isActive = true;
    setAccountsError(undefined);
    setExpenseCategoriesError(undefined);
    setIncomeCategoriesError(undefined);

    fetchTrackingAccounts()
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
    fetchExpenseCategories()
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
    fetchIncomeCategories()
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
      eyebrow="Settings"
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
        </Tabs.List>
        <Tabs.Panel id="accounts" className="settings-tab-panel">
          <div className="settings-tab-actions">
            <Button
              color="tertiary"
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
              <p className="user-management-message">Loading accounts...</p>
            ) : (
              <Table aria-label="Tracking accounts" size="sm">
                <Table.Header>
                  <Table.Head id="name" label="Name" isRowHeader />
                  <Table.Head id="currency" label="Currency" />
                  <Table.Head id="initialBalance" label="Initial balance" />
                  <Table.Head id="default" label="Default" />
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
                      <Table.Cell mobileRole="actions">
                        <div className="user-management-actions-cell">
                          <Table.MobileDetailsButton
                            label={`Show ${account.name} details`}
                          />
                          <Button
                            aria-label={`Edit ${account.name}`}
                            color="tertiary"
                            size="sm"
                            iconLeading={EditActionIcon}
                            onPress={() =>
                              navigate(`/settings/accounts/${account.id}`)
                            }
                          />
                        </div>
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
              color="tertiary"
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
              <p className="user-management-message">
                Loading expense categories...
              </p>
            ) : (
              <Table aria-label="Expense categories" size="sm">
                <Table.Header>
                  <Table.Head id="name" label="Name" isRowHeader />
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
                      <Table.Cell mobileRole="actions">
                        <div className="user-management-actions-cell">
                          <Button
                            aria-label={`Edit ${category.name}`}
                            color="tertiary"
                            size="sm"
                            iconLeading={EditActionIcon}
                            onPress={() =>
                              navigate(
                                `/settings/expense-categories/${category.id}`,
                              )
                            }
                          />
                        </div>
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
              color="tertiary"
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
              <p className="user-management-message">
                Loading income categories...
              </p>
            ) : (
              <Table aria-label="Income categories" size="sm">
                <Table.Header>
                  <Table.Head id="name" label="Name" isRowHeader />
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
                      <Table.Cell mobileRole="actions">
                        <div className="user-management-actions-cell">
                          <Button
                            aria-label={`Edit ${category.name}`}
                            color="tertiary"
                            size="sm"
                            iconLeading={EditActionIcon}
                            onPress={() =>
                              navigate(
                                `/settings/income-categories/${category.id}`,
                              )
                            }
                          />
                        </div>
                      </Table.Cell>
                    </Table.Row>
                  ))}
                </Table.Body>
              </Table>
            )}
          </TableCard.Root>
        </Tabs.Panel>
      </Tabs>
    </PageLayout>
  );
}

function EditActionIcon(props: ComponentProps<typeof Edit02>) {
  return <Edit02 {...props} data-action-icon="edit" />;
}
