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
import { PageLayout } from "@/components/PageLayout";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { Button } from "@/components/untitled/base/buttons/button";
import { Input, InputBase } from "@/components/untitled/base/input/input";
import { InputGroup } from "@/components/untitled/base/input/input-group";
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
  const [dateTime, setDateTime] = useState(toDateTimeLocalValue(new Date()));
  const [amount, setAmount] = useState(formatMoneyInput(0, "AUD"));
  const [notes, setNotes] = useState("");
  const [error, setError] = useState<string>();
  const [accountError, setAccountError] = useState<string>();
  const [categoryError, setCategoryError] = useState<string>();
  const [dateTimeError, setDateTimeError] = useState<string>();
  const [amountError, setAmountError] = useState<string>();
  const [isSubmitting, setIsSubmitting] = useState(false);

  const isEditing = mode === "edit";
  const selectedAccount = accounts?.find(
    (account) => account.id === trackingAccountId,
  );
  const currency = selectedAccount?.currency ?? "AUD";

  useEffect(() => {
    let isActive = true;
    Promise.all([fetchTrackingAccounts(), fetchExpenseCategories()])
      .then(([loadedAccounts, loadedCategories]) => {
        if (!isActive) {
          return;
        }
        setAccounts(loadedAccounts);
        setCategories(loadedCategories);
        setTrackingAccountId(
          (currentAccountId) =>
            currentAccountId ??
            loadedAccounts.find((account) => account.isDefault)?.id ??
            loadedAccounts[0]?.id,
        );
        setCategoryId(
          (currentCategoryId) => currentCategoryId ?? loadedCategories[0]?.id,
        );
      })
      .catch(() =>
        setError(
          "Expense form options could not be loaded. Try again in a moment.",
        ),
      );

    return () => {
      isActive = false;
    };
  }, []);

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
        setDateTime(toDateTimeLocalValue(new Date(loadedExpense.dateTime)));
        setAmount(
          formatMoneyInput(
            loadedExpense.amountMinor,
            loadedExpense.trackingAccount.currency,
          ),
        );
        setNotes(loadedExpense.notes ?? "");
      })
      .catch(() =>
        setError("Expense could not be loaded. Try again in a moment."),
      );

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
    const parsedDateTime = new Date(dateTime);
    const nextAccountError = trackingAccountId
      ? undefined
      : "Choose an account.";
    const nextCategoryError = categoryId ? undefined : "Choose a category.";
    const nextDateTimeError = Number.isNaN(parsedDateTime.getTime())
      ? "Enter a valid date and time."
      : undefined;
    const nextAmountError =
      amountMinor === undefined || amountMinor <= 0
        ? "Enter a valid amount greater than zero."
        : undefined;
    setAccountError(nextAccountError);
    setCategoryError(nextCategoryError);
    setDateTimeError(nextDateTimeError);
    setAmountError(nextAmountError);
    if (
      nextAccountError ||
      nextCategoryError ||
      nextDateTimeError ||
      nextAmountError ||
      !trackingAccountId ||
      !categoryId ||
      amountMinor === undefined
    ) {
      return;
    }

    const payload: SaveExpense = {
      trackingAccountId,
      categoryId,
      dateTime: parsedDateTime.toISOString(),
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
      navigate("/tracking", { replace: true });
    } catch {
      setError("Expense could not be saved. Try again in a moment.");
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <PageLayout
      eyebrow="Tracking"
      title={isEditing ? "Edit expense" : "Add expense"}
      description="Record spending against an account and expense category."
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
          <Input
            label="Date and time"
            name="dateTime"
            type="datetime-local"
            size="md"
            value={dateTime}
            isRequired
            validationBehavior="aria"
            isInvalid={Boolean(dateTimeError)}
            hint={dateTimeError}
            onChange={(nextDateTime) => {
              setDateTime(nextDateTime);
              setDateTimeError(undefined);
            }}
          />
          <InputGroup
            label="Amount"
            validationBehavior="aria"
            isInvalid={Boolean(amountError)}
            hint={amountError}
            trailingAddon={
              <InputGroup.Prefix className="tracking-account-amount-addon">
                {currency}
              </InputGroup.Prefix>
            }
          >
            <InputBase
              name="amount"
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
              onPress={() => navigate("/tracking")}
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
      </section>
    </PageLayout>
  );
}

function toDateTimeLocalValue(date: Date) {
  const offsetDate = new Date(
    date.getTime() - date.getTimezoneOffset() * 60_000,
  );
  return offsetDate.toISOString().slice(0, 16);
}
