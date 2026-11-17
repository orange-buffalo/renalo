import { type FormEvent, useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router";
import { ApiError } from "@/api/client";
import {
  type ExpenseRefundMatch,
  type RefundSearch,
  saveExpenseRefund,
  searchExpenseRefunds,
} from "@/api/expenseRefunds";
import { fetchTrackingAccounts } from "@/api/trackingAccounts";
import { FormLoadingOverlay } from "@/components/FormLoadingOverlay";
import { MoneyInput } from "@/components/MoneyInput";
import { PageLayout } from "@/components/PageLayout";
import { SearchableDropdown } from "@/components/SearchableDropdown";
import { TableEmptyState } from "@/components/TableEmptyState";
import { TableLoadingState } from "@/components/TableLoadingState";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { Button } from "@/components/untitled/base/buttons/button";
import {
  RadioButton,
  RadioGroup,
} from "@/components/untitled/base/radio-buttons/radio-buttons";
import { formatMoney, parseMoneyInput } from "@/utils/money";

const periods: { id: RefundSearch["period"]; label: string }[] = [
  { id: "THREE_MONTHS", label: "Last 3 months" },
  { id: "SIX_MONTHS", label: "Last 6 months" },
  { id: "YEAR", label: "Last year" },
  { id: "TWO_YEARS", label: "Last 2 years" },
  { id: "FULL_HISTORY", label: "Full history" },
];

export function ExpenseRefundPage() {
  const navigate = useNavigate();
  const [currencies, setCurrencies] = useState<string[]>([]);
  const [currency, setCurrency] = useState("AUD");
  const [amount, setAmount] = useState("");
  const [period, setPeriod] = useState<RefundSearch["period"]>("SIX_MONTHS");
  const [loading, setLoading] = useState(true);
  const [searching, setSearching] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const [matches, setMatches] = useState<ExpenseRefundMatch[]>();
  const [search, setSearch] = useState<RefundSearch>();
  const [selected, setSelected] = useState("");
  const requestId = useRef(0);
  const matchKey = (match: ExpenseRefundMatch) =>
    `${match.kind}-${match.transactionId}`;
  const chosen = matches?.find((match) => matchKey(match) === selected);

  useEffect(() => {
    let active = true;
    fetchTrackingAccounts({ includeArchived: true })
      .then((accounts) => {
        if (!active) return;
        setCurrencies(
          [...new Set(accounts.map((account) => account.currency))].sort(),
        );
        setCurrency(
          accounts.find((account) => account.isDefault)?.currency ?? "AUD",
        );
        setLoading(false);
      })
      .catch(() => {
        if (!active) return;
        setError("Could not load currencies. Reload the page to try again.");
        setLoading(false);
      });
    return () => {
      active = false;
      requestId.current += 1;
    };
  }, []);

  function invalidate() {
    requestId.current += 1;
    setMatches(undefined);
    setSearch(undefined);
    setSelected("");
    setSearching(false);
    setError(undefined);
  }

  async function findMatches(event: FormEvent) {
    event.preventDefault();
    const amountMinor = parseMoneyInput(amount, currency);
    if (
      amountMinor === undefined ||
      !Number.isSafeInteger(amountMinor) ||
      amountMinor <= 0
    ) {
      setError("Enter a valid refund amount greater than zero.");
      return;
    }
    invalidate();
    const id = ++requestId.current;
    const query = { currency, amountMinor, period };
    setSearching(true);
    try {
      const results = await searchExpenseRefunds(query);
      if (id !== requestId.current) return;
      setSearch(query);
      setMatches(results);
    } catch {
      if (id === requestId.current)
        setError("Could not find matching expenses. Please try again.");
    } finally {
      if (id === requestId.current) setSearching(false);
    }
  }

  async function save() {
    if (!chosen || !search) return;
    setSaving(true);
    setError(undefined);
    try {
      await saveExpenseRefund(search, chosen);
      navigate("/expenses", { replace: true });
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 409) {
        invalidate();
        setError(
          "These expenses have changed. Find matches again before recording the refund.",
        );
      } else setError("Could not record the refund. Please try again.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <PageLayout
      title="Record refund"
      description="Find the original expense and remove it from your spending."
    >
      <section className="standard-page-panel tracking-account-panel form-loading-container">
        <form
          className="tracking-account-form"
          onSubmit={findMatches}
          inert={saving}
        >
          <div className="tracking-account-form-wide space-y-2 text-sm text-tertiary">
            <p>
              Enter the full amount refunded and choose its currency. Renalo
              finds exact matches across all your accounts in that currency,
              including archived accounts. No currency conversion is used.
            </p>
            <p>
              A match can be one expense, one recurring payment, or all payments
              recorded through today in a recurring series. The date range finds
              expenses and series with a payment in that range; a series total
              always includes older payments too. Future payments do not count
              toward the refund amount.
            </p>
            <p>
              Saving permanently deletes the selected expense, rather than
              adding income. For a recurring payment, that payment and all
              following payments are deleted and the series stops there. For a
              whole series, all its expenses are deleted and future payments
              stop. Earlier payments are kept only when you select a single
              recurring payment.
            </p>
          </div>
          {error && (
            <Alert
              tone="error"
              title={error}
              className="tracking-account-form-wide"
            />
          )}
          <SearchableDropdown
            label="Currency"
            placeholder="Choose currency"
            selectedKey={currency}
            items={currencies.map((id) => ({ id, label: id }))}
            isDisabled={saving}
            onSelectionChange={(value) => {
              invalidate();
              setCurrency(value);
              setAmount("");
            }}
          />
          <MoneyInput
            label="Refund amount"
            name="refundAmount"
            value={amount}
            currency={currency}
            onChange={(value) => {
              if (
                parseMoneyInput(value, currency) !==
                parseMoneyInput(amount, currency)
              )
                invalidate();
              setAmount(value);
            }}
          />
          <SearchableDropdown
            label="Search period"
            placeholder="Choose period"
            selectedKey={period}
            items={periods}
            isDisabled={saving}
            onSelectionChange={(value) => {
              invalidate();
              setPeriod(value as RefundSearch["period"]);
            }}
          />
          <div className="flex items-end">
            <Button
              type="submit"
              color="secondary"
              size="sm"
              isLoading={searching}
              isDisabled={loading || saving || !currencies.length}
            >
              Find matches
            </Button>
          </div>
          <div className="tracking-account-form-wide" aria-live="polite">
            {searching && (
              <TableLoadingState label="Finding matching expenses" />
            )}
            {matches && matches.length === 0 && (
              <TableEmptyState title="No matching expenses. Try a longer period or check the amount and currency." />
            )}
            {matches && matches.length > 0 && search && (
              <div className="space-y-4">
                <h2 className="text-lg font-semibold text-primary">
                  Matching expenses ({matches.length})
                </h2>
                <RadioGroup
                  aria-label="Matching expenses"
                  value={selected}
                  onChange={setSelected}
                  isDisabled={saving}
                >
                  {matches.map((match) => (
                    <RadioButton
                      key={matchKey(match)}
                      value={matchKey(match)}
                      className="rounded-xl border border-secondary p-4"
                      label={`${match.kind === "RECURRING_SERIES" ? "Whole recurring series" : match.stopsRecurrence ? "Single recurring payment" : "Single expense"} · ${formatMoney(search.amountMinor, search.currency)}`}
                      hint={
                        <span className="block space-y-2">
                          {match.payments.map((payment) => (
                            <span
                              key={payment.id}
                              className="block"
                              data-testid="refund-payment"
                            >
                              {payment.date} · {payment.category.name} ·{" "}
                              {payment.trackingAccount.name} ·{" "}
                              {formatMoney(
                                payment.amountMinor,
                                payment.trackingAccount.currency,
                              )}{" "}
                              · {payment.notes || "No notes"}
                              {payment.recurrence && (
                                <span className="block">
                                  {payment.recurrence.description}
                                </span>
                              )}
                            </span>
                          ))}
                          <span className="block font-medium">
                            Deletes {match.removedExpenseCount} expense
                            {match.removedExpenseCount === 1 ? "" : "s"}
                            {match.stopsRecurrence
                              ? " and stops future payments."
                              : "."}
                          </span>
                        </span>
                      }
                    />
                  ))}
                </RadioGroup>
              </div>
            )}
          </div>
          {chosen && (
            <Alert
              tone="warning"
              title="This cannot be undone"
              className="tracking-account-form-wide"
            >
              {chosen.removedExpenseCount} expense
              {chosen.removedExpenseCount === 1 ? " will" : "s will"} be
              permanently deleted.
              {chosen.stopsRecurrence
                ? " Future payments in this series will stop too."
                : ""}{" "}
              No income entry will be created.
            </Alert>
          )}
          <div className="tracking-account-actions">
            <Button
              color="tertiary"
              size="sm"
              isDisabled={saving}
              onPress={() => navigate("/expenses")}
            >
              Cancel
            </Button>
            <Button
              color="primary"
              size="sm"
              isDisabled={!chosen || searching}
              isLoading={saving}
              onPress={save}
            >
              Record refund
            </Button>
          </div>
        </form>
        <FormLoadingOverlay isLoading={loading} />
      </section>
    </PageLayout>
  );
}
