import { Edit02, Plus } from "@untitledui/icons";
import type { ComponentProps } from "react";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
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
  const [accounts, setAccounts] = useState<TrackingAccount[]>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    let isActive = true;
    setError(undefined);

    fetchTrackingAccounts()
      .then((nextAccounts) => {
        if (isActive) {
          setAccounts(nextAccounts);
        }
      })
      .catch(() => {
        if (isActive) {
          setError("Accounts could not be loaded. Try again in a moment.");
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
      actions={
        <Button
          color="tertiary"
          size="sm"
          iconLeading={Plus}
          onPress={() => navigate("/settings/accounts/create")}
        >
          Add new account
        </Button>
      }
    >
      <Tabs selectedKey="accounts" className="settings-tabs">
        <div className="settings-tabs-list-row">
          <Tabs.List
            size="md"
            type="button-brand"
            aria-label="Settings sections"
          >
            <Tabs.Item id="accounts">Accounts</Tabs.Item>
          </Tabs.List>
        </div>
        <Tabs.Panel id="accounts" className="settings-tab-panel">
          <TableCard.Root size="sm">
            {error && (
              <p
                className="user-management-message user-management-error"
                role="alert"
              >
                {error}
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
                      <Table.Cell>
                        {formatMoney(
                          account.initialBalanceMinor,
                          account.currency,
                        )}
                      </Table.Cell>
                      <Table.Cell>
                        <BadgeWithDot
                          color={account.isDefault ? "success" : "gray"}
                          size="sm"
                        >
                          {account.isDefault ? "Default" : "No"}
                        </BadgeWithDot>
                      </Table.Cell>
                      <Table.Cell>
                        <div className="user-management-actions-cell">
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
      </Tabs>
    </PageLayout>
  );
}

function EditActionIcon(props: ComponentProps<typeof Edit02>) {
  return <Edit02 {...props} data-action-icon="edit" />;
}
