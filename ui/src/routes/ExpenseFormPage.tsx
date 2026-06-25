import { CalendarDate } from "@internationalized/date";
import { type FormEvent, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router";
import {
  type ExpenseCategory,
  fetchExpenseCategories,
} from "@/api/expenseCategories";
import {
  createExpense,
  fetchExpense,
  type SaveExpense,
  updateExpense,
} from "@/api/expenses";
import {
  fetchTrackingAccounts,
  type TrackingAccount,
} from "@/api/trackingAccounts";
import { FormLoadingOverlay } from "@/components/FormLoadingOverlay";
import { MoneyInput } from "@/components/MoneyInput";
import { PageLayout } from "@/components/PageLayout";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { DatePicker } from "@/components/untitled/application/date-picker/date-picker";
import { Button } from "@/components/untitled/base/buttons/button";
import { Label } from "@/components/untitled/base/input/label";
import { Select } from "@/components/untitled/base/select/select";
import { formatMoneyInput, parseMoneyInput } from "@/utils/money";

export function CreateExpensePage() {
  return <ExpenseFormPage mode="create" />;
}

export function EditExpensePage() {
  return <ExpenseFormPage mode="edit" />;
}

function ExpenseFormPage({ mode }: { mode: "create" | "edit" }) {
  const navigate = useNavigate();
  const { expenseId } = useParams();
  const [accounts, setAccounts] = useState<TrackingAccount[]>();
  const [categories, setCategories] = useState<ExpenseCategory[]>();
  const [trackingAccountId, setTrackingAccountId] = useState<number>();
  const [categoryId, setCategoryId] = useState<number>();
  const [date, setDate] = useState<CalendarDate | null>(
    dateToCalendarDate(new Date()),
  );
  const [amount, setAmount] = useState(formatMoneyInput(0, "AUD"));
  const [notes, setNotes] = useState("");
  const [error, setError] = useState<string>();
  const [accountError, setAccountError] = useState<string>();
  const [categoryError, setCategoryError] = useState<string>();
  const [dateError, setDateError] = useState<string>();
  const [amountError, setAmountError] = useState<string>();
  const [isLoadingOptions, setIsLoadingOptions] = useState(true);
  const [isLoadingExpense, setIsLoadingExpense] = useState(mode === "edit");
  const [isSubmitting, setIsSubmitting] = useState(false);

  const isEditing = mode === "edit";
  const selectedAccount = accounts?.find(
    (account) => account.id === trackingAccountId,
  );
  const currency = selectedAccount?.currency ?? "AUD";
  const isFormLoading = isLoadingOptions || isLoadingExpense;

  useEffect(() => {
    let isActive = true;
    Promise.all([fetchTrackingAccounts(), fetchExpenseCategories()])
      .then(([loadedAccounts, loadedCategories]) => {
        if (!isActive) {
          return;
        }
        setAccounts(loadedAccounts);
        setCategories(loadedCategories);
        const defaultAccount =
          loadedAccounts.find((account) => account.isDefault) ??
          loadedAccounts[0];
        setTrackingAccountId(
          (currentAccountId) => currentAccountId ?? defaultAccount?.id,
        );
        if (!isEditing && defaultAccount) {
          setAmount(formatMoneyInput(0, defaultAccount.currency));
        }
        setCategoryId(
          (currentCategoryId) => currentCategoryId ?? loadedCategories[0]?.id,
        );
        setIsLoadingOptions(false);
      })
      .catch(() => {
        if (isActive) {
          setError(
            "Expense form options could not be loaded. Try again in a moment.",
          );
          setIsLoadingOptions(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, [isEditing]);

  useEffect(() => {
    if (!isEditing || !expenseId) {
      return;
    }

    let isActive = true;
    fetchExpense(Number(expenseId))
      .then((loadedExpense) => {
        if (!isActive) {
          return;
        }
        setTrackingAccountId(loadedExpense.trackingAccount.id);
        setCategoryId(loadedExpense.category.id);
        setDate(dateToCalendarDate(new Date(loadedExpense.dateTime)));
        setAmount(
          formatMoneyInput(
            loadedExpense.amountMinor,
            loadedExpense.trackingAccount.currency,
          ),
        );
        setNotes(loadedExpense.notes ?? "");
        setIsLoadingExpense(false);
      })
      .catch(() => {
        if (isActive) {
          setError("Expense could not be loaded. Try again in a moment.");
          setIsLoadingExpense(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, [expenseId, isEditing]);

  function handleAccountChange(nextAccountId: number) {
    const previousCurrency = currency;
    const nextAccount = accounts?.find(
      (account) => account.id === nextAccountId,
    );
    setTrackingAccountId(nextAccountId);
    setAccountError(undefined);
    if (nextAccount) {
      const parsedAmount = parseMoneyInput(amount, previousCurrency) ?? 0;
      setAmount(formatMoneyInput(parsedAmount, nextAccount.currency));
    }
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const amountMinor = parseMoneyInput(amount, currency);
    const nextAccountError = trackingAccountId
      ? undefined
      : "Choose an account.";
    const nextCategoryError = categoryId ? undefined : "Choose a category.";
    const nextDateError = date ? undefined : "Choose a date.";
    const nextAmountError =
      amountMinor === undefined || amountMinor <= 0
        ? "Enter a valid amount greater than zero."
        : undefined;
    setAccountError(nextAccountError);
    setCategoryError(nextCategoryError);
    setDateError(nextDateError);
    setAmountError(nextAmountError);
    if (
      nextAccountError ||
      nextCategoryError ||
      nextDateError ||
      nextAmountError ||
      !trackingAccountId ||
      !categoryId ||
      !date ||
      amountMinor === undefined
    ) {
      return;
    }

    const payload: SaveExpense = {
      trackingAccountId,
      categoryId,
      dateTime: dateWithCurrentLocalTimeToIso(date),
      amountMinor,
      notes: notes.trim() || null,
    };
    setIsSubmitting(true);
    setError(undefined);

    try {
      if (isEditing) {
        await updateExpense(Number(expenseId), payload);
      } else {
        await createExpense(payload);
      }
      navigate("/expenses", { replace: true });
    } catch {
      setError("Expense could not be saved. Try again in a moment.");
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <PageLayout
      title={isEditing ? "Edit expense" : "Add expense"}
      description="Record spending against an account and expense category."
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
          <Select
            label="Category"
            placeholder="Choose category"
            size="md"
            isRequired
            selectedKey={categoryId ? String(categoryId) : null}
            isInvalid={Boolean(categoryError)}
            hint={categoryError}
            items={categories?.map((category) => ({
              id: String(category.id),
              label: category.name,
            }))}
            onSelectionChange={(key) => {
              setCategoryId(Number(key));
              setCategoryError(undefined);
            }}
          >
            {(item) => <Select.Item {...item} />}
          </Select>
          <MoneyInput
            label="Amount"
            name="amount"
            value={amount}
            currency={currency}
            isInvalid={Boolean(amountError)}
            hint={amountError}
            onChange={(nextAmount) => {
              setAmount(nextAmount);
              setAmountError(undefined);
            }}
          />
          <div className="expense-date-field">
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
            {dateError && <p className="expense-field-error">{dateError}</p>}
          </div>
          <Select
            label="Account"
            placeholder="Choose account"
            size="md"
            isRequired
            selectedKey={trackingAccountId ? String(trackingAccountId) : null}
            isInvalid={Boolean(accountError)}
            hint={accountError}
            items={accounts?.map((account) => ({
              id: String(account.id),
              label: account.name,
              supportingText: account.currency,
            }))}
            onSelectionChange={(key) => handleAccountChange(Number(key))}
          >
            {(item) => <Select.Item {...item} />}
          </Select>
          <div className="expense-notes-field">
            <Label>Notes</Label>
            <textarea
              name="notes"
              aria-label="Notes"
              className="expense-notes-textarea"
              value={notes}
              rows={4}
              onChange={(event) => setNotes(event.target.value)}
            />
          </div>
          <div aria-hidden="true" className="tracking-account-form-spacer" />
          <div className="tracking-account-actions">
            <Button
              color="tertiary"
              size="sm"
              onPress={() => navigate("/expenses")}
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
              {isEditing ? "Save expense" : "Create expense"}
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

function dateWithCurrentLocalTimeToIso(date: CalendarDate) {
  const now = new Date();
  return new Date(
    date.year,
    date.month - 1,
    date.day,
    now.getHours(),
    now.getMinutes(),
    now.getSeconds(),
    now.getMilliseconds(),
  ).toISOString();
}
