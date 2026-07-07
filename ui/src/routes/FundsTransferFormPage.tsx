import { CalendarDate } from "@internationalized/date";
import { type FormEvent, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router";
import {
  createFundsTransfer,
  fetchFundsTransfer,
  type SaveFundsTransfer,
  updateFundsTransfer,
} from "@/api/fundsTransfers";
import {
  fetchTrackingAccounts,
  type TrackingAccount,
} from "@/api/trackingAccounts";
import { FormLoadingOverlay } from "@/components/FormLoadingOverlay";
import { MoneyInput } from "@/components/MoneyInput";
import { PageLayout } from "@/components/PageLayout";
import { SearchableDropdown } from "@/components/SearchableDropdown";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { DatePicker } from "@/components/untitled/application/date-picker/date-picker";
import { Button } from "@/components/untitled/base/buttons/button";
import { Input } from "@/components/untitled/base/input/input";
import { Label } from "@/components/untitled/base/input/label";
import { loadStoredAccountId, storeAccountId } from "@/utils/accountSelection";
import {
  currencyFractionDigits,
  formatMoneyInput,
  parseMoneyInput,
} from "@/utils/money";

export function CreateFundsTransferPage() {
  return <FundsTransferFormPage mode="create" />;
}

export function EditFundsTransferPage() {
  return <FundsTransferFormPage mode="edit" />;
}

function FundsTransferFormPage({ mode }: { mode: "create" | "edit" }) {
  const navigate = useNavigate();
  const { transferId } = useParams();
  const [accounts, setAccounts] = useState<TrackingAccount[]>();
  const [sourceAccountId, setSourceAccountId] = useState<number>();
  const [targetAccountId, setTargetAccountId] = useState<number>();
  const [date, setDate] = useState<CalendarDate | null>(
    dateToCalendarDate(new Date()),
  );
  const [sourceAmount, setSourceAmount] = useState("");
  const [targetAmount, setTargetAmount] = useState("");
  const [exchangeRate, setExchangeRate] = useState("");
  const [error, setError] = useState<string>();
  const [sourceAccountError, setSourceAccountError] = useState<string>();
  const [targetAccountError, setTargetAccountError] = useState<string>();
  const [dateError, setDateError] = useState<string>();
  const [sourceAmountError, setSourceAmountError] = useState<string>();
  const [targetAmountError, setTargetAmountError] = useState<string>();
  const [exchangeRateError, setExchangeRateError] = useState<string>();
  const [isLoadingOptions, setIsLoadingOptions] = useState(true);
  const [isLoadingTransfer, setIsLoadingTransfer] = useState(mode === "edit");
  const [isSubmitting, setIsSubmitting] = useState(false);

  const isEditing = mode === "edit";
  const sourceAccount = accounts?.find(
    (account) => account.id === sourceAccountId,
  );
  const targetAccount = accounts?.find(
    (account) => account.id === targetAccountId,
  );
  const sourceCurrency = sourceAccount?.currency ?? "AUD";
  const targetCurrency = targetAccount?.currency ?? sourceCurrency;
  const isCrossCurrency = Boolean(
    sourceAccount && targetAccount && sourceCurrency !== targetCurrency,
  );
  const isFormLoading = isLoadingOptions || isLoadingTransfer;

  useEffect(() => {
    let isActive = true;
    fetchTrackingAccounts()
      .then((loadedAccounts) => {
        if (!isActive) {
          return;
        }
        setAccounts(loadedAccounts);
        const storedSourceId = loadStoredAccountId(
          "renalo.transfer.sourceAccountId",
        );
        const storedTargetId = loadStoredAccountId(
          "renalo.transfer.targetAccountId",
        );
        const preselectedSource =
          storedSourceId !== undefined
            ? loadedAccounts.find((a) => a.id === storedSourceId)
            : undefined;
        const preselectedTarget =
          storedTargetId !== undefined
            ? loadedAccounts.find((a) => a.id === storedTargetId)
            : undefined;
        const defaultAccount =
          loadedAccounts.find((account) => account.isDefault) ??
          loadedAccounts[0];
        const fallbackTarget = loadedAccounts.find(
          (account) => account.id !== (preselectedSource ?? defaultAccount)?.id,
        );
        setSourceAccountId(
          (currentAccountId) =>
            currentAccountId ?? preselectedSource?.id ?? defaultAccount?.id,
        );
        setTargetAccountId(
          (currentAccountId) =>
            currentAccountId ?? preselectedTarget?.id ?? fallbackTarget?.id,
        );
        if (!isEditing && defaultAccount) {
          setSourceAmount("");
          setTargetAmount("");
        }
        setIsLoadingOptions(false);
      })
      .catch(() => {
        if (isActive) {
          setError(
            "Transfer options could not be loaded. Try again in a moment.",
          );
          setIsLoadingOptions(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, [isEditing]);

  useEffect(() => {
    if (!isEditing || !transferId) {
      return;
    }

    let isActive = true;
    fetchFundsTransfer(Number(transferId))
      .then((loadedTransfer) => {
        if (!isActive) {
          return;
        }
        setSourceAccountId(loadedTransfer.sourceAccount.id);
        setTargetAccountId(loadedTransfer.targetAccount.id);
        setSourceAmount(
          formatMoneyInput(
            loadedTransfer.sourceAmountMinor,
            loadedTransfer.sourceAccount.currency,
          ),
        );
        setTargetAmount(
          formatMoneyInput(
            loadedTransfer.targetAmountMinor,
            loadedTransfer.targetAccount.currency,
          ),
        );
        if (
          loadedTransfer.sourceAccount.currency !==
          loadedTransfer.targetAccount.currency
        ) {
          setExchangeRate(
            formatExchangeRate(
              loadedTransfer.sourceAmountMinor,
              loadedTransfer.sourceAccount.currency,
              loadedTransfer.targetAmountMinor,
              loadedTransfer.targetAccount.currency,
            ),
          );
        }
        setDate(isoDateToCalendarDate(loadedTransfer.date));
        setIsLoadingTransfer(false);
      })
      .catch(() => {
        if (isActive) {
          setError("Transfer could not be loaded. Try again in a moment.");
          setIsLoadingTransfer(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, [isEditing, transferId]);

  function handleSourceAccountChange(nextAccountId: number) {
    const nextAccount = accounts?.find(
      (account) => account.id === nextAccountId,
    );
    const nextSourceCurrency = nextAccount?.currency ?? sourceCurrency;
    const nextSourceAmount = sourceAmount
      ? formatMoneyInput(
          parseMoneyInput(sourceAmount, sourceCurrency) ?? 0,
          nextSourceCurrency,
        )
      : "";
    setSourceAccountId(nextAccountId);
    storeAccountId("renalo.transfer.sourceAccountId", nextAccountId);
    setSourceAmount(nextSourceAmount);
    syncExchangeRateFromAmounts(
      nextSourceAmount,
      nextSourceCurrency,
      targetAmount,
      targetCurrency,
      Boolean(targetAccount && nextSourceCurrency !== targetCurrency),
    );
    setSourceAccountError(undefined);
    if (nextAccountId !== targetAccountId) {
      setTargetAccountError(undefined);
    }
  }

  function handleTargetAccountChange(nextAccountId: number) {
    const nextAccount = accounts?.find(
      (account) => account.id === nextAccountId,
    );
    const nextTargetCurrency = nextAccount?.currency ?? targetCurrency;
    const nextTargetAmount = targetAmount
      ? formatMoneyInput(
          parseMoneyInput(targetAmount, targetCurrency) ?? 0,
          nextTargetCurrency,
        )
      : "";
    setTargetAccountId(nextAccountId);
    storeAccountId("renalo.transfer.targetAccountId", nextAccountId);
    setTargetAmount(nextTargetAmount);
    syncExchangeRateFromAmounts(
      sourceAmount,
      sourceCurrency,
      nextTargetAmount,
      nextTargetCurrency,
      Boolean(sourceAccount && sourceCurrency !== nextTargetCurrency),
    );
    setTargetAccountError(undefined);
    if (nextAccountId !== sourceAccountId) {
      setSourceAccountError(undefined);
    }
  }

  function handleSourceAmountChange(nextAmount: string) {
    setSourceAmount(nextAmount);
    setSourceAmountError(undefined);
    syncExchangeRateFromAmounts(
      nextAmount,
      sourceCurrency,
      targetAmount,
      targetCurrency,
      isCrossCurrency,
    );
  }

  function handleTargetAmountChange(nextAmount: string) {
    setTargetAmount(nextAmount);
    setTargetAmountError(undefined);
    syncExchangeRateFromAmounts(
      sourceAmount,
      sourceCurrency,
      nextAmount,
      targetCurrency,
      isCrossCurrency,
    );
  }

  function handleExchangeRateChange(nextRate: string) {
    setExchangeRate(nextRate);
    setExchangeRateError(undefined);
    const parsedRate = parseExchangeRate(nextRate);
    const sourceAmountMinor = parseMoneyInput(sourceAmount, sourceCurrency);
    if (
      parsedRate === undefined ||
      sourceAmountMinor === undefined ||
      sourceAmountMinor <= 0
    ) {
      return;
    }
    setTargetAmount(
      formatMoneyInput(
        calculateTargetAmountMinor(
          sourceAmountMinor,
          sourceCurrency,
          targetCurrency,
          parsedRate,
        ),
        targetCurrency,
      ),
    );
    setTargetAmountError(undefined);
  }

  function syncExchangeRateFromAmounts(
    nextSourceAmount: string,
    nextSourceCurrency: string,
    nextTargetAmount: string,
    nextTargetCurrency: string,
    shouldShowExchangeRate: boolean,
  ) {
    if (!shouldShowExchangeRate) {
      setExchangeRate("");
      setExchangeRateError(undefined);
      return;
    }
    const sourceAmountMinor = parseMoneyInput(
      nextSourceAmount,
      nextSourceCurrency,
    );
    const targetAmountMinor = parseMoneyInput(
      nextTargetAmount,
      nextTargetCurrency,
    );
    if (
      sourceAmountMinor === undefined ||
      sourceAmountMinor <= 0 ||
      targetAmountMinor === undefined ||
      targetAmountMinor <= 0
    ) {
      return;
    }
    setExchangeRate(
      formatExchangeRate(
        sourceAmountMinor,
        nextSourceCurrency,
        targetAmountMinor,
        nextTargetCurrency,
      ),
    );
    setExchangeRateError(undefined);
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const sourceAmountMinor = parseMoneyInput(sourceAmount, sourceCurrency);
    const targetAmountMinor = isCrossCurrency
      ? parseMoneyInput(targetAmount, targetCurrency)
      : undefined;
    const nextSourceAccountError = sourceAccountId
      ? undefined
      : "Choose a source account.";
    const nextTargetAccountError = !targetAccountId
      ? "Choose a target account."
      : sourceAccountId === targetAccountId
        ? "Choose different source and target accounts."
        : undefined;
    const nextDateError = date ? undefined : "Choose a date.";
    const nextSourceAmountError =
      sourceAmountMinor === undefined || sourceAmountMinor <= 0
        ? "Enter a valid amount greater than zero."
        : undefined;
    const nextTargetAmountError =
      isCrossCurrency &&
      (targetAmountMinor === undefined || targetAmountMinor <= 0)
        ? "Enter a valid target amount greater than zero."
        : undefined;
    const nextExchangeRateError =
      isCrossCurrency && parseExchangeRate(exchangeRate) === undefined
        ? "Enter a valid exchange rate greater than zero."
        : undefined;

    setSourceAccountError(nextSourceAccountError);
    setTargetAccountError(nextTargetAccountError);
    setDateError(nextDateError);
    setSourceAmountError(nextSourceAmountError);
    setTargetAmountError(nextTargetAmountError);
    setExchangeRateError(nextExchangeRateError);
    if (
      nextSourceAccountError ||
      nextTargetAccountError ||
      nextDateError ||
      nextSourceAmountError ||
      nextTargetAmountError ||
      nextExchangeRateError ||
      !sourceAccountId ||
      !targetAccountId ||
      !date ||
      sourceAmountMinor === undefined
    ) {
      return;
    }

    const payload: SaveFundsTransfer = {
      sourceAccountId,
      targetAccountId,
      sourceAmountMinor,
      date: calendarDateToIsoDate(date),
    };
    if (isCrossCurrency && targetAmountMinor !== undefined) {
      payload.targetAmountMinor = targetAmountMinor;
    }

    setIsSubmitting(true);
    setError(undefined);
    try {
      if (isEditing) {
        await updateFundsTransfer(Number(transferId), payload);
      } else {
        await createFundsTransfer(payload);
      }
      navigate("/transfers", { replace: true });
    } catch {
      setError("Transfer could not be saved. Try again in a moment.");
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <PageLayout
      title={isEditing ? "Edit transfer" : "Add transfer"}
      description="Move funds between tracking accounts."
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
          <SearchableDropdown
            label="Source account"
            placeholder="Choose source account"
            isRequired
            selectedKey={sourceAccountId ? String(sourceAccountId) : undefined}
            isInvalid={Boolean(sourceAccountError)}
            hint={sourceAccountError}
            items={accountItems(accounts)}
            onSelectionChange={(key) => handleSourceAccountChange(Number(key))}
          />
          <SearchableDropdown
            label="Target account"
            placeholder="Choose target account"
            isRequired
            selectedKey={targetAccountId ? String(targetAccountId) : undefined}
            isInvalid={Boolean(targetAccountError)}
            hint={targetAccountError}
            items={accountItems(accounts)}
            onSelectionChange={(key) => handleTargetAccountChange(Number(key))}
          />
          <MoneyInput
            label={isCrossCurrency ? "Source amount" : "Amount"}
            name="sourceAmount"
            value={sourceAmount}
            currency={sourceCurrency}
            isRequired
            isInvalid={Boolean(sourceAmountError)}
            hint={sourceAmountError}
            onChange={handleSourceAmountChange}
          />
          {isCrossCurrency ? (
            <MoneyInput
              label="Target amount"
              name="targetAmount"
              value={targetAmount}
              currency={targetCurrency}
              isRequired
              isInvalid={Boolean(targetAmountError)}
              hint={targetAmountError}
              onChange={handleTargetAmountChange}
            />
          ) : (
            <span className="tracking-account-form-spacer" aria-hidden="true" />
          )}
          <div className="transaction-date-field">
            <Label isRequired>Date</Label>
            <DatePicker
              value={date}
              onChange={(nextDate) => {
                setDate(
                  nextDate
                    ? new CalendarDate(
                        nextDate.year,
                        nextDate.month,
                        nextDate.day,
                      )
                    : null,
                );
                setDateError(undefined);
              }}
              size="md"
              aria-label="Date"
            />
            {dateError && (
              <p className="transaction-field-error">{dateError}</p>
            )}
          </div>
          {isCrossCurrency ? (
            <Input
              label="Exchange rate"
              name="exchangeRate"
              size="md"
              value={exchangeRate}
              inputMode="decimal"
              inputClassName="text-right"
              isRequired
              isInvalid={Boolean(exchangeRateError)}
              hint={exchangeRateError}
              className="funds-transfer-rate-input"
              onChange={handleExchangeRateChange}
            />
          ) : (
            <span className="tracking-account-form-spacer" aria-hidden="true" />
          )}
          <div className="tracking-account-actions">
            <Button
              color="tertiary"
              size="sm"
              onPress={() => navigate("/transfers")}
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
              {isEditing ? "Save transfer" : "Create transfer"}
            </Button>
          </div>
        </form>
        <FormLoadingOverlay isLoading={isFormLoading} />
      </section>
    </PageLayout>
  );
}

function accountItems(accounts?: TrackingAccount[]) {
  return (
    accounts?.map((account) => ({
      id: String(account.id),
      label: account.name,
      supportingText: account.currency,
    })) ?? []
  );
}

function dateToCalendarDate(date: Date) {
  return new CalendarDate(
    date.getFullYear(),
    date.getMonth() + 1,
    date.getDate(),
  );
}

function isoDateToCalendarDate(isoDate: string) {
  const [year, month, day] = isoDate.split("-").map(Number);
  return new CalendarDate(year, month, day);
}

function calendarDateToIsoDate(date: CalendarDate) {
  const month = String(date.month).padStart(2, "0");
  const day = String(date.day).padStart(2, "0");
  return `${date.year}-${month}-${day}`;
}

function parseExchangeRate(value: string) {
  const normalized = value.trim().replace(",", ".");
  if (normalized === "") {
    return undefined;
  }
  const parsedRate = Number(normalized);
  return Number.isFinite(parsedRate) && parsedRate > 0 ? parsedRate : undefined;
}

function formatExchangeRate(
  sourceAmountMinor: number,
  sourceCurrency: string,
  targetAmountMinor: number,
  targetCurrency: string,
) {
  const sourceAmount = minorUnitsToMajor(sourceAmountMinor, sourceCurrency);
  const targetAmount = minorUnitsToMajor(targetAmountMinor, targetCurrency);
  if (sourceAmount <= 0 || targetAmount <= 0) {
    return "";
  }
  return formatRate(targetAmount / sourceAmount);
}

function calculateTargetAmountMinor(
  sourceAmountMinor: number,
  sourceCurrency: string,
  targetCurrency: string,
  exchangeRate: number,
) {
  const sourceAmount = minorUnitsToMajor(sourceAmountMinor, sourceCurrency);
  const targetFractionDigits = currencyFractionDigits(targetCurrency);
  return Math.round(sourceAmount * exchangeRate * 10 ** targetFractionDigits);
}

function minorUnitsToMajor(minorUnits: number, currency: string) {
  return minorUnits / 10 ** currencyFractionDigits(currency);
}

function formatRate(rate: number) {
  return rate.toFixed(8).replace(/\.?0+$/, "");
}
