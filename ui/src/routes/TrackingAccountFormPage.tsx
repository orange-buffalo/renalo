import { type FormEvent, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router";
import {
  createTrackingAccount,
  fetchTrackingAccount,
  type SaveTrackingAccount,
  type TrackingAccount,
  updateTrackingAccount,
} from "@/api/trackingAccounts";
import { PageLayout } from "@/components/PageLayout";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { Button } from "@/components/untitled/base/buttons/button";
import { Checkbox } from "@/components/untitled/base/checkbox/checkbox";
import { Input, InputBase } from "@/components/untitled/base/input/input";
import { InputGroup } from "@/components/untitled/base/input/input-group";
import { Select } from "@/components/untitled/base/select/select";
import {
  formatMoneyInput,
  getCurrencyOptions,
  parseMoneyInput,
} from "@/utils/money";

const currencyOptions = getCurrencyOptions();

export function CreateTrackingAccountPage() {
  return <TrackingAccountFormPage mode="create" />;
}

export function EditTrackingAccountPage() {
  return <TrackingAccountFormPage mode="edit" />;
}

function TrackingAccountFormPage({ mode }: { mode: "create" | "edit" }) {
  const navigate = useNavigate();
  const { accountId } = useParams();
  const [account, setAccount] = useState<TrackingAccount>();
  const [name, setName] = useState("");
  const [currency, setCurrency] = useState("AUD");
  const [amount, setAmount] = useState(formatMoneyInput(0, "AUD"));
  const [isDefault, setIsDefault] = useState(false);
  const [error, setError] = useState<string>();
  const [isSubmitting, setIsSubmitting] = useState(false);

  const isEditing = mode === "edit";

  useEffect(() => {
    if (!isEditing || !accountId) {
      return;
    }

    let isActive = true;
    fetchTrackingAccount(Number(accountId))
      .then((loadedAccount) => {
        if (!isActive) {
          return;
        }
        setAccount(loadedAccount);
        setName(loadedAccount.name);
        setCurrency(loadedAccount.currency);
        setAmount(
          formatMoneyInput(
            loadedAccount.initialBalanceMinor,
            loadedAccount.currency,
          ),
        );
        setIsDefault(loadedAccount.isDefault);
      })
      .catch(() =>
        setError("Account could not be loaded. Try again in a moment."),
      );

    return () => {
      isActive = false;
    };
  }, [accountId, isEditing]);

  function handleCurrencyChange(nextCurrency: string) {
    const parsed = parseMoneyInput(amount, currency) ?? 0;
    setCurrency(nextCurrency);
    setAmount(formatMoneyInput(parsed, nextCurrency));
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const initialBalanceMinor = parseMoneyInput(amount, currency);
    if (name.trim() === "" || initialBalanceMinor === undefined) {
      setError("Provide an account name and a valid initial balance.");
      return;
    }

    const payload: SaveTrackingAccount = {
      name,
      currency,
      initialBalanceMinor,
      isDefault,
    };
    setIsSubmitting(true);
    setError(undefined);

    try {
      if (isEditing) {
        await updateTrackingAccount(Number(accountId), payload);
      } else {
        await createTrackingAccount(payload);
      }
      navigate("/settings", { replace: true });
    } catch {
      setError("Account could not be saved. Try again in a moment.");
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <PageLayout
      eyebrow="Settings"
      title={isEditing ? "Edit account" : "Add new account"}
      description="Accounts define where tracked budget activity belongs."
    >
      <section className="standard-page-panel tracking-account-panel">
        <form className="tracking-account-form" onSubmit={handleSubmit}>
          {error && (
            <Alert
              tone="error"
              title={error}
              className="tracking-account-form-wide"
            />
          )}
          {isEditing && (
            <Alert
              tone="warning"
              title="Currency changes affect related entries."
              className="tracking-account-form-wide"
            >
              <p>
                Changing currency will implicitly change the currency of all
                incomes, expenses, transfers, and settings linked to this
                account.
              </p>
            </Alert>
          )}
          <Input
            label="Name"
            name="name"
            size="md"
            value={name}
            isRequired
            onChange={setName}
          />
          <Select.ComboBox
            label="Currency"
            items={currencyOptions}
            selectedKey={currency}
            onSelectionChange={(key) => handleCurrencyChange(String(key))}
            size="md"
            shortcut={false}
            placeholder="Search currencies"
          >
            {(item) => (
              <Select.Item
                id={item.id}
                label={item.label}
                supportingText={item.supportingText}
              />
            )}
          </Select.ComboBox>
          <InputGroup
            label="Initial amount"
            hint="This is used for analytics."
            trailingAddon={<InputGroup.Prefix>{currency}</InputGroup.Prefix>}
          >
            <InputBase
              name="initialBalance"
              value={amount}
              inputMode="decimal"
              onChange={(event) => setAmount(event.target.value)}
              onBlur={() =>
                setAmount(
                  formatMoneyInput(
                    parseMoneyInput(amount, currency) ?? 0,
                    currency,
                  ),
                )
              }
            />
          </InputGroup>
          <Checkbox
            className="tracking-account-default-checkbox"
            label="Default account"
            hint={
              account?.isDefault
                ? "To change the default, nominate another account. The default account defines the currency used for analytics."
                : "The default account defines the currency used for analytics."
            }
            isSelected={isDefault}
            isDisabled={account?.isDefault}
            onChange={setIsDefault}
          />
          <div className="tracking-account-actions">
            <Button
              color="tertiary"
              size="sm"
              onPress={() => navigate("/settings")}
              isDisabled={isSubmitting}
            >
              Cancel
            </Button>
            <Button
              color="primary"
              size="sm"
              type="submit"
              isLoading={isSubmitting}
            >
              {isEditing ? "Save account" : "Create account"}
            </Button>
          </div>
        </form>
      </section>
    </PageLayout>
  );
}
