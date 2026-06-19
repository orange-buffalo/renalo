import { ChevronDown, SearchLg } from "@untitledui/icons";
import { type FormEvent, useEffect, useMemo, useState } from "react";
import {
  Button as AriaButton,
  Input as AriaInput,
} from "react-aria-components";
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
import { Dropdown } from "@/components/untitled/base/dropdown/dropdown";
import { Input, InputBase } from "@/components/untitled/base/input/input";
import { InputGroup } from "@/components/untitled/base/input/input-group";
import { Label } from "@/components/untitled/base/input/label";
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
  const [currencySearch, setCurrencySearch] = useState("");
  const [amount, setAmount] = useState(formatMoneyInput(0, "AUD"));
  const [isDefault, setIsDefault] = useState(false);
  const [error, setError] = useState<string>();
  const [nameError, setNameError] = useState<string>();
  const [amountError, setAmountError] = useState<string>();
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
    setCurrencySearch("");
    setAmount(formatMoneyInput(parsed, nextCurrency));
  }

  const selectedCurrency = currencyOptions.find(
    (option) => option.id === currency,
  );
  const filteredCurrencyOptions = useMemo(() => {
    const normalizedSearch = currencySearch.trim().toLowerCase();
    if (!normalizedSearch) {
      return currencyOptions;
    }

    return currencyOptions.filter((option) =>
      `${option.label} ${option.supportingText}`
        .toLowerCase()
        .includes(normalizedSearch),
    );
  }, [currencySearch]);

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
            selectedCurrency={selectedCurrency}
            currencySearch={currencySearch}
            filteredCurrencyOptions={filteredCurrencyOptions}
            onSearchChange={setCurrencySearch}
            onCurrencyChange={handleCurrencyChange}
          />
          <InputGroup
            label="Initial amount"
            validationBehavior="aria"
            isInvalid={Boolean(amountError)}
            hint={amountError ?? "This is used for analytics."}
            trailingAddon={
              <InputGroup.Prefix className="tracking-account-amount-addon">
                {currency}
              </InputGroup.Prefix>
            }
          >
            <InputBase
              name="initialBalance"
              value={amount}
              inputMode="decimal"
              onChange={(event) => {
                setAmount(event.target.value);
                setAmountError(undefined);
              }}
              onBlur={() =>
                setAmount((currentAmount) => {
                  const parsedAmount = parseMoneyInput(currentAmount, currency);
                  return parsedAmount === undefined
                    ? currentAmount
                    : formatMoneyInput(parsedAmount, currency);
                })
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

type CurrencyDropdownProps = {
  currency: string;
  selectedCurrency?: { label: string; supportingText: string };
  currencySearch: string;
  filteredCurrencyOptions: Array<{
    id: string;
    label: string;
    supportingText: string;
  }>;
  onSearchChange: (search: string) => void;
  onCurrencyChange: (currency: string) => void;
};

function CurrencyDropdown({
  currency,
  selectedCurrency,
  currencySearch,
  filteredCurrencyOptions,
  onSearchChange,
  onCurrencyChange,
}: CurrencyDropdownProps) {
  const [isOpen, setIsOpen] = useState(false);

  return (
    <div className="tracking-account-currency-field">
      <Label>Currency</Label>
      <Dropdown.Root isOpen={isOpen} onOpenChange={setIsOpen}>
        <AriaButton
          aria-label="Currency"
          className="tracking-account-currency-trigger"
        >
          <span className="tracking-account-currency-value">
            <span>{selectedCurrency?.label ?? currency}</span>
            <span>{selectedCurrency?.supportingText ?? currency}</span>
          </span>
          <ChevronDown
            aria-hidden="true"
            className="tracking-account-currency-chevron"
          />
        </AriaButton>
        <Dropdown.Popover
          placement="bottom left"
          className="tracking-account-currency-popover"
        >
          <div className="tracking-account-currency-search-wrap">
            <SearchLg
              aria-hidden="true"
              className="tracking-account-currency-search-icon"
            />
            <AriaInput
              aria-label="Search currencies"
              className="tracking-account-currency-search"
              placeholder="Search"
              value={currencySearch}
              onChange={(event) => onSearchChange(event.target.value)}
            />
          </div>
          <Dropdown.Separator />
          <Dropdown.Menu
            selectionMode="single"
            selectedKeys={[currency]}
            onAction={(key) => {
              onCurrencyChange(String(key));
              setIsOpen(false);
            }}
            className="tracking-account-currency-menu"
          >
            {filteredCurrencyOptions.map((option) => (
              <Dropdown.Item
                key={option.id}
                id={option.id}
                textValue={`${option.label} ${option.supportingText}`}
              >
                <span className="tracking-account-currency-option">
                  <span>{option.label}</span>
                  <span>{option.supportingText}</span>
                </span>
              </Dropdown.Item>
            ))}
          </Dropdown.Menu>
        </Dropdown.Popover>
      </Dropdown.Root>
    </div>
  );
}
