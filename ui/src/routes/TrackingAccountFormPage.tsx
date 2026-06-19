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
import { ComboBox } from "@/components/untitled/base/select/combobox";
import { SelectItem } from "@/components/untitled/base/select/select-item";
import {
  Tooltip,
  TooltipTrigger,
} from "@/components/untitled/base/tooltip/tooltip";
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
      <form
        className="standard-page-panel settings-form"
        onSubmit={handleSubmit}
      >
        {error && <Alert tone="error" title={error} />}
        {isEditing && (
          <Alert
            tone="warning"
            title="Currency changes affect related entries."
          >
            <p>
              Changing currency will implicitly change the currency of all
              incomes, expenses, transfers, and settings linked to this account.
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
        <ComboBox
          label="Currency"
          tooltip="All entries linked to the account will be treated as in this currency."
          items={currencyOptions}
          selectedKey={currency}
          onSelectionChange={(key) => handleCurrencyChange(String(key))}
          size="md"
          shortcut={false}
        >
          {(item) => (
            <SelectItem
              id={item.id}
              label={item.label}
              supportingText={item.supportingText}
            />
          )}
        </ComboBox>
        <InputGroup
          label="Initial amount"
          tooltip="This is used for analytics."
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
        <Tooltip
          title={
            account?.isDefault
              ? "To change the default, nominate another account. Default account is used to define the currency in which to display analytics."
              : "Default account is used to define the currency in which to display analytics."
          }
          placement="top"
        >
          <TooltipTrigger className="settings-default-tooltip-trigger">
            <Checkbox
              label="Default account"
              isSelected={isDefault}
              isDisabled={account?.isDefault}
              onChange={setIsDefault}
            />
          </TooltipTrigger>
        </Tooltip>
        <div className="settings-form-actions">
          <Button
            color="secondary"
            size="sm"
            onPress={() => navigate("/settings")}
          >
            Cancel
          </Button>
          <Button
            color="primary"
            size="sm"
            type="submit"
            isLoading={isSubmitting}
          >
            Save account
          </Button>
        </div>
      </form>
    </PageLayout>
  );
}
