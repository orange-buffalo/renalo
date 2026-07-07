import { type FormEvent, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router";
import {
  createTrackingAccount,
  fetchTrackingAccount,
  type SaveTrackingAccount,
  type TrackingAccount,
  updateTrackingAccount,
} from "@/api/trackingAccounts";
import { FormLoadingOverlay } from "@/components/FormLoadingOverlay";
import { MoneyInput } from "@/components/MoneyInput";
import { PageLayout } from "@/components/PageLayout";
import { SearchableDropdown } from "@/components/SearchableDropdown";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { Button } from "@/components/untitled/base/buttons/button";
import { Checkbox } from "@/components/untitled/base/checkbox/checkbox";
import { Input } from "@/components/untitled/base/input/input";
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
  const [loadedCurrency, setLoadedCurrency] = useState<string>();
  const [amount, setAmount] = useState("");
  const [isDefault, setIsDefault] = useState(false);
  const [error, setError] = useState<string>();
  const [nameError, setNameError] = useState<string>();
  const [amountError, setAmountError] = useState<string>();
  const [isLoading, setIsLoading] = useState(mode === "edit");
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
        setLoadedCurrency(loadedAccount.currency);
        setAmount(
          formatMoneyInput(
            loadedAccount.initialBalanceMinor,
            loadedAccount.currency,
          ),
        );
        setIsDefault(loadedAccount.isDefault);
        setIsLoading(false);
      })
      .catch(() => {
        if (isActive) {
          setError("Account could not be loaded. Try again in a moment.");
          setIsLoading(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, [accountId, isEditing]);

  function handleCurrencyChange(nextCurrency: string) {
    setCurrency(nextCurrency);
    if (amount) {
      const parsed = parseMoneyInput(amount, currency) ?? 0;
      setAmount(formatMoneyInput(parsed, nextCurrency));
    }
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const initialBalanceMinor = parseMoneyInput(amount, currency);
    const nextNameError =
      name.trim() === "" ? "Enter an account name." : undefined;
    const nextAmountError =
      initialBalanceMinor === undefined
        ? "Enter a valid initial amount."
        : undefined;
    setNameError(nextNameError);
    setAmountError(nextAmountError);
    if (nextNameError || initialBalanceMinor === undefined) {
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
      title={isEditing ? "Edit account" : "Add new account"}
      description="Accounts define where tracked budget activity belongs."
    >
      <section className="standard-page-panel tracking-account-panel form-loading-container">
        <form className="tracking-account-form" onSubmit={handleSubmit}>
          {error && (
            <Alert
              tone="error"
              title={error}
              className="tracking-account-form-wide"
            />
          )}
          {isEditing && loadedCurrency && currency !== loadedCurrency && (
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
            validationBehavior="aria"
            isInvalid={Boolean(nameError)}
            hint={nameError}
            onChange={(nextName) => {
              setName(nextName);
              setNameError(undefined);
            }}
          />
          <CurrencyDropdown
            currency={currency}
            onCurrencyChange={handleCurrencyChange}
          />
          <MoneyInput
            label="Initial amount"
            name="initialBalance"
            value={amount}
            currency={currency}
            isRequired
            isInvalid={Boolean(amountError)}
            hint={amountError ?? "This is used for analytics."}
            onChange={(nextAmount) => {
              setAmount(nextAmount);
              setAmountError(undefined);
            }}
          />
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
        <FormLoadingOverlay isLoading={isLoading} />
      </section>
    </PageLayout>
  );
}

type CurrencyDropdownProps = {
  currency: string;
  onCurrencyChange: (currency: string) => void;
};

function CurrencyDropdown({
  currency,
  onCurrencyChange,
}: CurrencyDropdownProps) {
  return (
    <SearchableDropdown
      label="Currency"
      placeholder={currency}
      items={currencyOptions}
      selectedKey={currency}
      isRequired
      className="searchable-dropdown-field tracking-account-currency-field"
      onSelectionChange={onCurrencyChange}
    />
  );
}
