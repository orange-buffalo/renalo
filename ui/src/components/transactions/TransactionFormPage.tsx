import { CalendarDate } from "@internationalized/date";
import { type FormEvent, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router";
import {
  fetchTrackingAccounts,
  type TrackingAccount,
} from "@/api/trackingAccounts";
import {
  createTransaction,
  fetchTransaction,
  type SaveTransaction,
  type Transaction,
  type TransactionApiConfig,
  updateTransaction,
} from "@/api/transactions";
import { FormLoadingOverlay } from "@/components/FormLoadingOverlay";
import { MoneyInput } from "@/components/MoneyInput";
import { PageLayout } from "@/components/PageLayout";
import { SearchableDropdown } from "@/components/SearchableDropdown";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { DatePicker } from "@/components/untitled/application/date-picker/date-picker";
import { Button } from "@/components/untitled/base/buttons/button";
import { Checkbox } from "@/components/untitled/base/checkbox/checkbox";
import { Label } from "@/components/untitled/base/input/label";
import { loadStoredAccountId, storeAccountId } from "@/utils/accountSelection";
import { formatMoneyInput, parseMoneyInput } from "@/utils/money";

type RecurrenceScheduleOption =
  | "DAILY"
  | "WEEKLY"
  | "BIWEEKLY"
  | "MONTHLY"
  | "CUSTOM";
type RecurrenceIntervalOption = NonNullable<
  SaveTransaction["recurrence"]
>["interval"];
type RecurrenceRepetitionOption = "ENDLESS" | `${number}`;
type RecurringEditScope = NonNullable<SaveTransaction["recurringEditScope"]>;

type StoredRecurrenceConfiguration = {
  schedule: RecurrenceScheduleOption;
  customFrequency: number;
  customInterval: RecurrenceIntervalOption;
  repetitions: RecurrenceRepetitionOption;
};

export type TransactionCategory = {
  id: number;
  name: string;
};

const recurrenceScheduleOptions: Array<{
  id: RecurrenceScheduleOption;
  label: string;
}> = [
  { id: "DAILY", label: "Daily" },
  { id: "WEEKLY", label: "Weekly" },
  { id: "BIWEEKLY", label: "Biweekly" },
  { id: "MONTHLY", label: "Monthly" },
  { id: "CUSTOM", label: "Custom" },
];

const customFrequencyOptions = Array.from({ length: 100 }, (_, index) => ({
  id: String(index + 1),
  label: String(index + 1),
}));

const customIntervalOptions: Array<{
  id: RecurrenceIntervalOption;
  label: string;
}> = [
  { id: "DAY", label: "Days" },
  { id: "WEEK", label: "Weeks" },
  { id: "MONTH", label: "Months" },
];

const recurrenceRepetitionOptions: Array<{
  id: RecurrenceRepetitionOption;
  label: string;
}> = [
  { id: "ENDLESS", label: "Endless" },
  ...Array.from({ length: 99 }, (_, index) => {
    const repetitions = index + 2;
    return {
      id: String(repetitions) as `${number}`,
      label: String(repetitions),
    };
  }),
];

const recurringEditScopeOptions: Array<{
  id: RecurringEditScope;
  label: string;
}> = [
  { id: "THIS_OCCURRENCE_ONLY", label: "This occurrence only" },
  {
    id: "THIS_AND_ALL_FOLLOWING_OCCURRENCES",
    label: "This and all following occurrences",
  },
  { id: "ALL_OCCURRENCES", label: "All occurrences" },
];

export type TransactionFormConfig = {
  api: TransactionApiConfig;
  routeBasePath: string;
  storageKey: string;
  accountStorageKey: string;
  categoryLabel: string;
  categoryPlaceholder: string;
  categoryError: string;
  optionsLoadError: string;
  loadError: string;
  saveError: string;
  title: (isEditing: boolean) => string;
  description: string;
  recurringCheckboxLabel: string;
  recurringCheckboxHint: string;
  createButtonLabel: string;
  saveButtonLabel: string;
  fetchCategories: () => Promise<TransactionCategory[]>;
};

export function TransactionFormPage({
  config,
  mode,
}: {
  config: TransactionFormConfig;
  mode: "create" | "edit";
}) {
  const navigate = useNavigate();
  const { transactionId } = useParams();
  const [accounts, setAccounts] = useState<TrackingAccount[]>();
  const [categories, setCategories] = useState<TransactionCategory[]>();
  const [trackingAccountId, setTrackingAccountId] = useState<number>();
  const [categoryId, setCategoryId] = useState<number>();
  const [date, setDate] = useState<CalendarDate | null>(
    dateToCalendarDate(new Date()),
  );
  const [isRecurring, setIsRecurring] = useState(false);
  const [recurrenceSchedule, setRecurrenceSchedule] =
    useState<RecurrenceScheduleOption>("WEEKLY");
  const [customRecurrenceFrequency, setCustomRecurrenceFrequency] = useState(1);
  const [customRecurrenceInterval, setCustomRecurrenceInterval] =
    useState<RecurrenceIntervalOption>("WEEK");
  const [recurrenceEndDate, setRecurrenceEndDate] =
    useState<CalendarDate | null>(null);
  const [recurrenceRepetitions, setRecurrenceRepetitions] =
    useState<RecurrenceRepetitionOption>("ENDLESS");
  const [loadedTransaction, setLoadedTransaction] = useState<Transaction>();
  const [recurringEditScope, setRecurringEditScope] =
    useState<RecurringEditScope>("THIS_OCCURRENCE_ONLY");
  const [amount, setAmount] = useState("");
  const [notes, setNotes] = useState("");
  const [error, setError] = useState<string>();
  const [accountError, setAccountError] = useState<string>();
  const [categoryError, setCategoryError] = useState<string>();
  const [dateError, setDateError] = useState<string>();
  const [amountError, setAmountError] = useState<string>();
  const [recurrenceEndDateError, setRecurrenceEndDateError] =
    useState<string>();
  const [isLoadingOptions, setIsLoadingOptions] = useState(true);
  const [isLoadingTransaction, setIsLoadingTransaction] = useState(
    mode === "edit",
  );
  const [isSubmitting, setIsSubmitting] = useState(false);

  const isEditing = mode === "edit";
  const isEditingRecurringTransaction = Boolean(loadedTransaction?.recurrence);
  const selectedAccount = accounts?.find(
    (account) => account.id === trackingAccountId,
  );
  const currency = selectedAccount?.currency ?? "AUD";
  const isFormLoading = isLoadingOptions || isLoadingTransaction;

  useEffect(() => {
    let isActive = true;
    Promise.all([fetchTrackingAccounts(), config.fetchCategories()])
      .then(([loadedAccounts, loadedCategories]) => {
        if (!isActive) {
          return;
        }
        setAccounts(loadedAccounts);
        setCategories(loadedCategories);
        const storedAccountId = loadStoredAccountId(config.accountStorageKey);
        const preselectedAccount =
          storedAccountId !== undefined
            ? loadedAccounts.find((a) => a.id === storedAccountId)
            : undefined;
        const defaultAccount =
          preselectedAccount ??
          loadedAccounts.find((account) => account.isDefault) ??
          loadedAccounts[0];
        setTrackingAccountId(
          (currentAccountId) => currentAccountId ?? defaultAccount?.id,
        );
        if (!isEditing && defaultAccount) {
          setAmount("");
        }
        setCategoryId((currentCategoryId) => currentCategoryId ?? undefined);
        setIsLoadingOptions(false);
      })
      .catch(() => {
        if (isActive) {
          setError(config.optionsLoadError);
          setIsLoadingOptions(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, [config, isEditing]);

  useEffect(() => {
    if (!isEditing || !transactionId) {
      return;
    }

    let isActive = true;
    fetchTransaction(config.api, Number(transactionId))
      .then((loadedTransaction) => {
        if (!isActive) {
          return;
        }
        setTrackingAccountId(loadedTransaction.trackingAccount.id);
        setCategoryId(loadedTransaction.category.id);
        setDate(isoDateToCalendarDate(loadedTransaction.date));
        setLoadedTransaction(loadedTransaction);
        setAmount(
          formatMoneyInput(
            loadedTransaction.amountMinor,
            loadedTransaction.trackingAccount.currency,
          ),
        );
        setNotes(loadedTransaction.notes ?? "");
        setIsLoadingTransaction(false);
      })
      .catch(() => {
        if (isActive) {
          setError(config.loadError);
          setIsLoadingTransaction(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, [config, transactionId, isEditing]);

  function handleAccountChange(nextAccountId: number) {
    setTrackingAccountId(nextAccountId);
    storeAccountId(config.accountStorageKey, nextAccountId);
    setAccountError(undefined);
  }

  function handleDateChange(nextDate: CalendarDate | null) {
    setDate(nextDate);
    setDateError(undefined);
    if (!nextDate) {
      return;
    }
    if (recurrenceRepetitions !== "ENDLESS") {
      setRecurrenceEndDate(
        calculateEndDateForRepetitions(
          nextDate,
          recurrencePayloadFor(
            recurrenceSchedule,
            customRecurrenceFrequency,
            customRecurrenceInterval,
          ),
          Number(recurrenceRepetitions),
        ),
      );
      setRecurrenceEndDateError(undefined);
    } else if (recurrenceEndDate) {
      setRecurrenceRepetitions(
        calculateRepetitionsForEndDate(
          nextDate,
          recurrencePayloadFor(
            recurrenceSchedule,
            customRecurrenceFrequency,
            customRecurrenceInterval,
          ),
          recurrenceEndDate,
        ),
      );
    }
  }

  function handleRecurrenceScheduleChange(
    nextSchedule: RecurrenceScheduleOption,
  ) {
    setRecurrenceSchedule(nextSchedule);
    syncEndDateFromSchedule(
      recurrencePayloadFor(
        nextSchedule,
        customRecurrenceFrequency,
        customRecurrenceInterval,
      ),
    );
  }

  function handleCustomFrequencyChange(nextFrequency: number) {
    setCustomRecurrenceFrequency(nextFrequency);
    syncEndDateFromSchedule(
      recurrencePayloadFor(
        recurrenceSchedule,
        nextFrequency,
        customRecurrenceInterval,
      ),
    );
  }

  function handleCustomIntervalChange(nextInterval: RecurrenceIntervalOption) {
    setCustomRecurrenceInterval(nextInterval);
    syncEndDateFromSchedule(
      recurrencePayloadFor(
        recurrenceSchedule,
        customRecurrenceFrequency,
        nextInterval,
      ),
    );
  }

  function syncEndDateFromSchedule(schedule: RecurrencePayload) {
    if (recurrenceRepetitions !== "ENDLESS" && date) {
      setRecurrenceEndDate(
        calculateEndDateForRepetitions(
          date,
          schedule,
          Number(recurrenceRepetitions),
        ),
      );
      setRecurrenceEndDateError(undefined);
    } else if (recurrenceEndDate && date) {
      setRecurrenceRepetitions(
        calculateRepetitionsForEndDate(date, schedule, recurrenceEndDate),
      );
    }
  }

  function handleRecurrenceEndDateChange(nextDate: CalendarDate | null) {
    setRecurrenceEndDate(nextDate);
    setRecurrenceEndDateError(undefined);
    setRecurrenceRepetitions(
      nextDate && date
        ? calculateRepetitionsForEndDate(
            date,
            recurrencePayloadFor(
              recurrenceSchedule,
              customRecurrenceFrequency,
              customRecurrenceInterval,
            ),
            nextDate,
          )
        : "ENDLESS",
    );
  }

  function handleRecurrenceRepetitionChange(
    nextRepetitions: RecurrenceRepetitionOption,
  ) {
    setRecurrenceRepetitions(nextRepetitions);
    setRecurrenceEndDate(
      nextRepetitions === "ENDLESS" || !date
        ? null
        : calculateEndDateForRepetitions(
            date,
            recurrencePayloadFor(
              recurrenceSchedule,
              customRecurrenceFrequency,
              customRecurrenceInterval,
            ),
            Number(nextRepetitions),
          ),
    );
    setRecurrenceEndDateError(undefined);
  }

  function restoreLastRecurrenceConfiguration() {
    const storedConfiguration = loadStoredRecurrenceConfiguration(
      config.storageKey,
    );
    if (!storedConfiguration) {
      return;
    }

    setRecurrenceSchedule(storedConfiguration.schedule);
    setCustomRecurrenceFrequency(storedConfiguration.customFrequency);
    setCustomRecurrenceInterval(storedConfiguration.customInterval);
    setRecurrenceRepetitions(storedConfiguration.repetitions);
    setRecurrenceEndDate(
      storedConfiguration.repetitions === "ENDLESS" || !date
        ? null
        : calculateEndDateForRepetitions(
            date,
            recurrencePayloadFor(
              storedConfiguration.schedule,
              storedConfiguration.customFrequency,
              storedConfiguration.customInterval,
            ),
            Number(storedConfiguration.repetitions),
          ),
    );
    setRecurrenceEndDateError(undefined);
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const amountMinor = parseMoneyInput(amount, currency);
    const nextAccountError = trackingAccountId
      ? undefined
      : "Choose an account.";
    const nextCategoryError = categoryId ? undefined : config.categoryError;
    const nextDateError = date ? undefined : "Choose a date.";
    const nextAmountError =
      amountMinor === undefined || amountMinor <= 0
        ? "Enter a valid amount greater than zero."
        : undefined;
    const nextRecurrenceEndDateError =
      isRecurring &&
      date &&
      recurrenceEndDate &&
      recurrenceEndDate.compare(date) < 0
        ? "Choose an end date on or after the transaction date."
        : undefined;
    setAccountError(nextAccountError);
    setCategoryError(nextCategoryError);
    setDateError(nextDateError);
    setAmountError(nextAmountError);
    setRecurrenceEndDateError(nextRecurrenceEndDateError);
    if (
      nextAccountError ||
      nextCategoryError ||
      nextDateError ||
      nextAmountError ||
      nextRecurrenceEndDateError ||
      !trackingAccountId ||
      !categoryId ||
      !date ||
      amountMinor === undefined
    ) {
      return;
    }

    const payload: SaveTransaction = {
      trackingAccountId,
      categoryId,
      date: calendarDateToIsoDate(date),
      amountMinor,
      notes: notes.trim() || null,
    };
    if (isRecurring && !isEditing) {
      payload.recurrence = {
        ...recurrencePayloadFor(
          recurrenceSchedule,
          customRecurrenceFrequency,
          customRecurrenceInterval,
        ),
        endDate: recurrenceEndDate
          ? calendarDateToIsoDate(recurrenceEndDate)
          : null,
      };
    }
    if (isEditingRecurringTransaction) {
      payload.recurringEditScope = recurringEditScope;
    }
    setIsSubmitting(true);
    setError(undefined);

    try {
      if (isEditing) {
        await updateTransaction(config.api, Number(transactionId), payload);
      } else {
        await createTransaction(config.api, payload);
        if (isRecurring) {
          storeRecurrenceConfiguration(config.storageKey, {
            schedule: recurrenceSchedule,
            customFrequency: customRecurrenceFrequency,
            customInterval: customRecurrenceInterval,
            repetitions: recurrenceRepetitions,
          });
        }
      }
      navigate(config.routeBasePath, { replace: true });
    } catch {
      setError(config.saveError);
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <PageLayout
      title={config.title(isEditing)}
      description={config.description}
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
            label={config.categoryLabel}
            placeholder={config.categoryPlaceholder}
            isRequired
            selectedKey={categoryId ? String(categoryId) : undefined}
            isInvalid={Boolean(categoryError)}
            hint={categoryError}
            items={
              categories?.map((category) => ({
                id: String(category.id),
                label: category.name,
              })) ?? []
            }
            onSelectionChange={(key) => {
              setCategoryId(Number(key));
              setCategoryError(undefined);
            }}
          />
          <MoneyInput
            label="Amount"
            name="amount"
            value={amount}
            currency={currency}
            isRequired
            isInvalid={Boolean(amountError)}
            hint={amountError}
            onChange={(nextAmount) => {
              setAmount(nextAmount);
              setAmountError(undefined);
            }}
          />
          <div className="transaction-date-field">
            <Label isRequired>Date</Label>
            <DatePicker
              value={date}
              isDisabled={isEditingRecurringTransaction}
              onChange={(nextDate) => {
                handleDateChange(
                  nextDate
                    ? new CalendarDate(
                        nextDate.year,
                        nextDate.month,
                        nextDate.day,
                      )
                    : null,
                );
              }}
              size="md"
              aria-label="Date"
            />
            {dateError && (
              <p className="transaction-field-error">{dateError}</p>
            )}
          </div>
          <SearchableDropdown
            label="Account"
            placeholder="Choose account"
            isRequired
            selectedKey={
              trackingAccountId ? String(trackingAccountId) : undefined
            }
            isInvalid={Boolean(accountError)}
            hint={accountError}
            items={
              accounts?.map((account) => ({
                id: String(account.id),
                label: account.name,
                supportingText: account.currency,
              })) ?? []
            }
            onSelectionChange={(key) => handleAccountChange(Number(key))}
          />
          <div className="transaction-notes-field">
            <Label>Notes</Label>
            <textarea
              name="notes"
              aria-label="Notes"
              className="transaction-notes-textarea"
              value={notes}
              rows={4}
              onChange={(event) => setNotes(event.target.value)}
            />
          </div>
          {!isEditing && (
            <div className="transaction-recurrence-section">
              <Checkbox
                className="transaction-recurring-checkbox"
                label={config.recurringCheckboxLabel}
                hint={config.recurringCheckboxHint}
                size="md"
                isSelected={isRecurring}
                onChange={(selected) => {
                  setIsRecurring(selected);
                  if (selected) {
                    restoreLastRecurrenceConfiguration();
                  }
                  if (!selected) {
                    setRecurrenceEndDate(null);
                    setRecurrenceRepetitions("ENDLESS");
                    setRecurrenceEndDateError(undefined);
                  }
                }}
              />
              {isRecurring && (
                <div className="transaction-recurrence-controls">
                  <SearchableDropdown
                    label="Repeat"
                    placeholder="Choose schedule"
                    selectedKey={recurrenceSchedule}
                    items={recurrenceScheduleOptions}
                    onSelectionChange={(key) =>
                      handleRecurrenceScheduleChange(
                        key as RecurrenceScheduleOption,
                      )
                    }
                  />
                  {recurrenceSchedule === "CUSTOM" && (
                    <div className="transaction-custom-recurrence-controls">
                      <SearchableDropdown
                        label="Repeat every"
                        placeholder="Choose interval"
                        selectedKey={String(customRecurrenceFrequency)}
                        items={customFrequencyOptions}
                        onSelectionChange={(key) =>
                          handleCustomFrequencyChange(Number(key))
                        }
                      />
                      <SearchableDropdown
                        label="Cadence"
                        placeholder="Choose cadence"
                        selectedKey={customRecurrenceInterval}
                        items={customIntervalOptions}
                        onSelectionChange={(key) =>
                          handleCustomIntervalChange(
                            key as RecurrenceIntervalOption,
                          )
                        }
                      />
                    </div>
                  )}
                  <div className="transaction-recurrence-end-controls">
                    <div className="transaction-date-field">
                      <Label>End date</Label>
                      <DatePicker
                        value={recurrenceEndDate}
                        onChange={(nextDate) => {
                          handleRecurrenceEndDateChange(
                            nextDate
                              ? new CalendarDate(
                                  nextDate.year,
                                  nextDate.month,
                                  nextDate.day,
                                )
                              : null,
                          );
                        }}
                        size="md"
                        aria-label="End date"
                      />
                      {recurrenceEndDateError && (
                        <p className="transaction-field-error">
                          {recurrenceEndDateError}
                        </p>
                      )}
                    </div>
                    <SearchableDropdown
                      label="End after repetitions"
                      placeholder="Choose repetitions"
                      selectedKey={recurrenceRepetitions}
                      items={recurrenceRepetitionOptions}
                      onSelectionChange={(key) =>
                        handleRecurrenceRepetitionChange(
                          key as RecurrenceRepetitionOption,
                        )
                      }
                    />
                  </div>
                </div>
              )}
            </div>
          )}
          {isEditingRecurringTransaction && loadedTransaction?.recurrence && (
            <div className="transaction-recurrence-section">
              <div className="transaction-recurrence-context">
                <p className="transaction-recurrence-context-label">
                  Recurring schedule
                </p>
                <p className="transaction-recurrence-context-value">
                  {loadedTransaction.recurrence.description}
                </p>
                <p className="transaction-recurrence-context-hint">
                  The first occurrence in this series is on{" "}
                  {formatShortDate(loadedTransaction.recurrence.startDate)}.
                </p>
                <p className="transaction-recurrence-context-hint">
                  Date and schedule cannot be edited. Delete and recreate the
                  relevant scope to change when this repeats.
                </p>
              </div>
              <SearchableDropdown
                label="Edit scope"
                placeholder="Choose scope"
                selectedKey={recurringEditScope}
                items={recurringEditScopeOptions}
                onSelectionChange={(key) =>
                  setRecurringEditScope(key as RecurringEditScope)
                }
              />
            </div>
          )}
          {isEditing && !isEditingRecurringTransaction && (
            <div aria-hidden="true" className="tracking-account-form-spacer" />
          )}
          <div className="tracking-account-actions">
            <Button
              color="tertiary"
              size="sm"
              onPress={() => navigate(config.routeBasePath)}
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
              {isEditing ? config.saveButtonLabel : config.createButtonLabel}
            </Button>
          </div>
        </form>
        <FormLoadingOverlay isLoading={isFormLoading} />
      </section>
    </PageLayout>
  );
}

function dateToCalendarDate(date: Date) {
  return new CalendarDate(
    date.getFullYear(),
    date.getMonth() + 1,
    date.getDate(),
  );
}

function isoDateToCalendarDate(date: string) {
  const [year, month, day] = date.split("-").map(Number);
  return new CalendarDate(year, month, day);
}

function calendarDateToIsoDate(date: CalendarDate) {
  return date.toString();
}

function formatShortDate(isoDate: string) {
  const [year, month, day] = isoDate.split("-").map(Number);
  return new Intl.DateTimeFormat("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
  }).format(new Date(year, month - 1, day));
}

type RecurrencePayload = {
  frequency: number;
  interval: RecurrenceIntervalOption;
};

function recurrencePayloadFor(
  schedule: RecurrenceScheduleOption,
  customFrequency: number,
  customInterval: RecurrenceIntervalOption,
): RecurrencePayload {
  switch (schedule) {
    case "DAILY":
      return { frequency: 1, interval: "DAY" as const };
    case "WEEKLY":
      return { frequency: 1, interval: "WEEK" as const };
    case "BIWEEKLY":
      return { frequency: 2, interval: "WEEK" as const };
    case "MONTHLY":
      return { frequency: 1, interval: "MONTH" as const };
    case "CUSTOM":
      return { frequency: customFrequency, interval: customInterval };
  }
}

function calculateEndDateForRepetitions(
  startDate: CalendarDate,
  schedule: RecurrencePayload,
  repetitions: number,
) {
  return addScheduleInterval(
    startDate,
    schedule,
    (repetitions - 1) * schedule.frequency,
  );
}

function calculateRepetitionsForEndDate(
  startDate: CalendarDate,
  schedule: RecurrencePayload,
  endDate: CalendarDate,
): RecurrenceRepetitionOption {
  let occurrenceDate = startDate;
  let repetitions = 1;
  while (repetitions <= 100 && occurrenceDate.compare(endDate) <= 0) {
    if (occurrenceDate.compare(endDate) === 0) {
      return repetitions >= 2
        ? (String(repetitions) as RecurrenceRepetitionOption)
        : "ENDLESS";
    }
    occurrenceDate = addScheduleInterval(
      occurrenceDate,
      schedule,
      schedule.frequency,
    );
    repetitions += 1;
  }

  const previousRepetitions = repetitions - 1;
  return previousRepetitions >= 2 && previousRepetitions <= 100
    ? (String(previousRepetitions) as RecurrenceRepetitionOption)
    : "ENDLESS";
}

function addScheduleInterval(
  date: CalendarDate,
  schedule: RecurrencePayload,
  amount: number,
) {
  switch (schedule.interval) {
    case "DAY":
      return date.add({ days: amount });
    case "WEEK":
      return date.add({ weeks: amount });
    case "MONTH":
      return date.add({ months: amount });
  }
}

function loadStoredRecurrenceConfiguration(storageKey: string) {
  try {
    const storedValue = window.localStorage.getItem(storageKey);
    if (!storedValue) {
      return undefined;
    }

    const parsedValue = JSON.parse(storedValue) as unknown;
    if (!isStoredRecurrenceConfiguration(parsedValue)) {
      return undefined;
    }

    return parsedValue;
  } catch (error) {
    console.warn("Stored recurrence configuration could not be loaded.", error);
    return undefined;
  }
}

function storeRecurrenceConfiguration(
  storageKey: string,
  configuration: StoredRecurrenceConfiguration,
) {
  try {
    window.localStorage.setItem(storageKey, JSON.stringify(configuration));
  } catch (error) {
    console.warn("Recurrence configuration could not be stored.", error);
  }
}

function isStoredRecurrenceConfiguration(
  value: unknown,
): value is StoredRecurrenceConfiguration {
  if (!value || typeof value !== "object") {
    return false;
  }
  const configuration = value as Partial<StoredRecurrenceConfiguration>;
  return (
    isRecurrenceScheduleOption(configuration.schedule) &&
    typeof configuration.customFrequency === "number" &&
    Number.isInteger(configuration.customFrequency) &&
    configuration.customFrequency >= 1 &&
    configuration.customFrequency <= 100 &&
    isRecurrenceIntervalOption(configuration.customInterval) &&
    isRecurrenceRepetitionOption(configuration.repetitions)
  );
}

function isRecurrenceScheduleOption(
  value: unknown,
): value is RecurrenceScheduleOption {
  return recurrenceScheduleOptions.some((option) => option.id === value);
}

function isRecurrenceIntervalOption(
  value: unknown,
): value is RecurrenceIntervalOption {
  return customIntervalOptions.some((option) => option.id === value);
}

function isRecurrenceRepetitionOption(
  value: unknown,
): value is RecurrenceRepetitionOption {
  return recurrenceRepetitionOptions.some((option) => option.id === value);
}
