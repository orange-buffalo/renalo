# Financial Calculations

This document defines the financial calculation rules used by Renalo. Monetary values are stored as signed or unsigned integer minor units and are never stored as floating-point values. For example, AUD 123.45 is stored as `12345`, JPY 123 is stored as `123`, and KWD 1.234 is stored as `1234`.

Unless a section says otherwise:

- A tracking account's ISO currency determines how its minor units are interpreted.
- Income, expense, transfer, and recurring amounts must be positive. Their direction determines whether they add to or subtract from a balance.
- Initial balances and account adjustments may be positive, zero, or negative.
- "Current" means as of the current UTC date, inclusive.
- Future-dated financial records do not affect a current balance.
- Monetary integer arithmetic must be exact. Overflow or underflow must fail; it must never wrap to another value.
- Amounts in different currencies are never summed into one total.

## 1. Currency Precision, Formatting, and Input

### Business rules

Each currency uses its ISO default fraction digits:

- JPY has zero digits: JPY 123 is `123` minor units.
- AUD has two digits: AUD 123.45 is `12345` minor units.
- KWD has three digits: KWD 1.234 is `1234` minor units.

Formatting converts minor units to major units and always renders the currency's expected number of fraction digits. UI values must be finite safe integers before they are formatted or sent to the API.

Money input is strict and locale-aware. Valid locale grouping and decimal separators are accepted. Malformed grouping, embedded text, exponent notation, multiple signs or separators, nonfinite values, and values outside JavaScript's safe-integer range are rejected.

When input contains more precision than the currency supports, it is rounded to the nearest minor unit. Exact ties round away from zero.

### Examples

| Input | Currency | Minor units | Notes |
|---|---:|---:|---|
| `123.45` | AUD | `12345` | Exact two-decimal input |
| `123.456` | AUD | `12346` | Rounded to nearest cent |
| `123.455` | AUD | `12346` | Tie rounds away from zero |
| `123.5` | JPY | `124` | Rounded to whole yen |
| `1.234` | KWD | `1234` | Exact three-decimal input |
| `12abc34` | AUD | rejected | Invalid embedded text |

### Edge cases

- Unsafe JavaScript integers are rejected rather than silently rounded.
- Invalid ISO currency codes are rejected.
- Negative zero is normalized as a zero monetary value.
- Formatting and parsing use decimal/BigInt arithmetic where precision matters; binary floating-point is not used for money parsing.

### Coverage

- `ui/src/utils/money.test.ts`
- `ui/src/utils/money.ts`

## 2. Ordinary Income and Expense Amounts

### Business rules

Income and expense rows store a positive `amountMinor`. The transaction type supplies the sign:

```text
income balance effect = +amountMinor
expense balance effect = -amountMinor
```

Zero and negative transaction amounts are invalid. One minor unit is the smallest valid amount.

Changing the selected account in an unsaved transaction form preserves the entered major-unit text, not the old currency's minor-unit count. The value is parsed using the newly selected account's currency when submitted.

### Example

An AUD account has an income of `20000` and an expense of `4500`:

```text
net transaction effect = 20000 - 4500 = 15500 minor units
```

If the user entered `12.34` and switches from AUD to JPY before saving, the input remains `12.34` and is rounded under JPY precision when submitted. It must not become JPY 1,234 merely because AUD uses two fraction digits.

### Edge cases

- Create and update operations reject zero and negative amounts.
- Category and account currencies do not alter the stored positive-direction rule.
- Moving an existing transaction to another account does not perform currency conversion; the submitted integer is interpreted in the destination account currency.

### Coverage

- `ExpenseApiTest`
- `IncomeApiTest`
- `ExpensesPagePlaywrightTest`
- `IncomesPagePlaywrightTest`

## 3. Recurring Transaction Amounts

### Business rules

A recurring rule stores one positive amount. Newly generated occurrences copy that amount. Generation is idempotent for each rule and instance date.

Scoped edits behave as follows:

- **This occurrence only:** update the selected transaction and lock it; do not change the rule.
- **This and following:** split the rule at the selected occurrence; update the selected and following unlocked occurrences.
- **All occurrences:** update the rule and all unlocked occurrences.
- A locked occurrence preserves its custom amount unless it is the selected occurrence in an explicit scoped edit.

### Example

A weekly rule for `10000` has three occurrences. The second occurrence is manually changed to `12000` and locked. Updating all occurrences to `9000` produces:

```text
first occurrence  = 9000
second occurrence = 12000 (locked)
third occurrence  = 9000
rule amount       = 9000
```

### Edge cases

- Rule and occurrence amounts must be greater than zero.
- Skipped recurrence dates produce no transaction and therefore no balance effect.
- Future generated occurrences are excluded from current balances until their date arrives.

### Coverage

- `RecurringExpenseGenerationServiceTest`
- `RecurringExpensePersistenceTest`
- recurring cases in `ExpenseApiTest` and `IncomeApiTest`

## 4. Funds Transfers

### Business rules

A transfer has a source amount in the source account currency and a target amount in the target account currency.

For same-currency accounts:

```text
targetAmountMinor = sourceAmountMinor
```

Any target amount supplied by the client is ignored.

For different-currency accounts, both positive amounts are supplied independently. The backend does not derive or persist an exchange rate:

```text
source account effect = -sourceAmountMinor
target account effect = +targetAmountMinor
```

### Example

An AUD-to-JPY transfer stores `10000` source minor units and `9500` target minor units:

```text
AUD account: -AUD 100.00
JPY account: +JPY 9,500
```

The two integers are not directly comparable and are never combined.

### Edge cases

- Source and target accounts must differ and belong to the user.
- Both applicable amounts must be greater than zero; one minor unit is valid.
- Archived accounts are excluded from normal creation UI.
- A failed update leaves the persisted transfer unchanged.

### Coverage

- `FundsTransferApiTest`
- `FundsTransfersPagePlaywrightTest`
- cross-currency dashboard coverage in `DashboardApiTest`

## 5. Transfer Exchange-Rate Display and Derivation

### Business rules

The transfer form defines the displayed rate as:

```text
1 source major unit = rate target major units
rate = target major amount / source major amount
```

The rate is a UI aid only. The source and target minor-unit amounts remain authoritative.

When a rate is edited, the target amount is calculated exactly from decimal input:

```text
targetMinor = round(
  sourceMinor × rate × 10^targetFractionDigits / 10^sourceFractionDigits
)
```

Rounding is to the nearest target minor unit, with a positive half-minor-unit tie rounded upward.

### Examples

```text
AUD 100.00 × 0.5 EUR/AUD = EUR 50.00
JPY 10,000 × 0.001234 KWD/JPY = KWD 12.340
JPY 1 × 0.005 AUD/JPY = AUD 0.01
```

### Edge cases

- Rates must be positive plain decimals. Exponent notation, grouping, malformed text, and zero/negative rates are rejected.
- Very small positive implied rates are rendered as nonzero decimals instead of being rounded to the invalid string `0`.
- A calculated target outside JavaScript's safe-integer range is rejected.
- Switching accounts preserves entered major-unit text and recalculates using the new currencies.

### Coverage

- `ui/src/utils/exchangeRate.test.ts`
- exchange-rate flows in `FundsTransfersPagePlaywrightTest`

## 6. Dashboard Account Balance

### Business rules

Each active account is calculated independently in its own currency:

```text
total balance = initial balance
              + nonfuture income
              - nonfuture expense
              - nonfuture outgoing transfer source amounts
              + nonfuture incoming transfer target amounts
              + nonfuture adjustments
```

Today is included. Future transactions, transfers, and adjustments are excluded. Archived accounts are omitted.

Every addition and subtraction is exact and throws on `Long` overflow or underflow.

### Example

```text
initial balance      10000
income               20000
expense              -4000
outgoing transfer    -7000
incoming transfer     2000
adjustment             500
total balance        21500
```

### Edge cases

- Negative final balances are valid.
- A cross-currency transfer uses the source-side amount for the source account and target-side amount for the target account.
- Adjustments do not count as inflow or outflow.
- Activity in another currency/account does not affect this balance.

### Coverage

- `DashboardApiTest`
- `AccountAdjustmentApiTest.adjustmentsAffectDashboardBalance`
- `FinancialMathTest`
- `DashboardPagePlaywrightTest`

## 7. Current-Month Inflow and Outflow

### Business rules

The current month is the UTC calendar month containing today.

```text
inflow = current-month nonfuture income
       + current-month nonfuture incoming transfer target amounts

outflow = current-month nonfuture expense
        + current-month nonfuture outgoing transfer source amounts
```

Initial balances and adjustments are excluded because they are balance corrections, not money flow.

### Example

For an AUD account with AUD 200 income, AUD 40 expense, AUD 70 outgoing transfer, and AUD 20 incoming transfer this month:

```text
inflow  = 20000 + 2000 = 22000
outflow = 4000 + 7000 = 11000
```

### Edge cases

- Previous-month records affect total balance but not current-month flow.
- Future records in the current month affect neither balance nor flow until their date.
- Month boundaries and today are inclusive under UTC dates.

### Coverage

- `DashboardApiTest`
- `DashboardPagePlaywrightTest`

## 8. Account Adjustment Balance

### Business rules

The adjustment page's base balance uses the same as-of-today transaction and transfer policy as the dashboard:

```text
base balance = initial balance
             + nonfuture income
             - nonfuture expense
             - nonfuture outgoing transfer source amounts
             + nonfuture incoming transfer target amounts

current balance = base balance + sum(nonfuture adjustments)
```

An adjustment is a signed delta. Zero is invalid.

### Examples

```text
base balance  = 10000
adjustment    = -2550
current       = 7450
```

If the user enters a target balance, the UI derives:

```text
adjustment delta = desired target - displayed current balance
```

The server stores the delta, not the target.

### Edge cases

- Positive and negative adjustments are valid.
- Future-dated imported adjustments are excluded until their date.
- Deletion must match both the account route and adjustment ID.
- Exact arithmetic prevents wrapped balances.
- A concurrent balance change can make a UI-derived target stale; the submitted delta remains authoritative.

### Coverage

- `AccountAdjustmentApiTest`
- `AccountAdjustmentsPagePlaywrightTest`
- `FinancialMathTest`

## 9. Tracking Account Merge

### Business rules

Only two accounts owned by the same user and using the same currency can be merged. The source account is deleted after all value-bearing references are preserved.

The merged initial balance is:

```text
target initial + source initial + internal-transfer net
```

For each direct transfer between source and target:

```text
internal-transfer net = target-side amount - source-side amount
```

Normally a same-currency internal transfer has equal amounts and a zero net. Including the net also preserves value for legacy or directly persisted unequal records.

Transactions, account adjustments, and recurring rules are reassigned to the target. Transfers between source and target are removed after their net is incorporated. Other transfer sides are reassigned.

### Example

```text
source initial = 100
target initial = -50
internal transfer source amount = 70
internal transfer target amount = 75
merged initial = -50 + 100 + (75 - 70) = 55
```

### Edge cases

- Negative initial balances and a negative merged balance are valid.
- Initial-balance/net arithmetic must not overflow.
- Source adjustments and recurring rules must not be deleted or left pointing at the removed account.
- The default-account invariant is preserved.

### Coverage

- merge cases in `TrackingAccountApiTest`
- `SettingsPagePlaywrightTest`
- `FinancialMathTest`

## 10. Tracking Account Currency Changes

### Business rules

The account currency owns the interpretation of all linked minor-unit integers. Changing an existing account currency does not convert historical values; it relabels their integer minor units under the new currency. The UI warns about this behavior.

### Example

An account with `1234` minor units changes from AUD to JPY:

```text
before: AUD 12.34
after:  JPY 1,234
```

This is intentionally different from switching an account selection on an unsaved transaction form, which preserves entered major-unit text.

### Edge cases

- No implicit exchange rate is available, so silent conversion is forbidden.
- Existing transactions, transfers, adjustments, and recurring rules retain their integers.
- Different fraction-digit currencies can cause large displayed-value changes; confirmation/warning is required.

### Coverage

- tracking-account update cases in `TrackingAccountApiTest`
- currency-change UI cases in `SettingsPagePlaywrightTest`

## 11. Planned Transaction Totals

### Business rules

Future planned transactions are grouped by account currency. Each currency is summed independently:

```text
planned total[currency] = sum(amountMinor for future rows in currency)
```

No exchange conversion or cross-currency grand total is produced.

### Example

```text
AUD 55.00 + AUD 21.00 = AUD 76.00
EUR 20.00 remains a separate EUR 20.00 total
```

### Edge cases

- Empty groups are omitted.
- Integer totals must remain safe in the browser; unsafe API monetary values are rejected at formatting boundaries.
- Recurring future occurrences participate like ordinary future transactions.

### Coverage

- planned sections in `ExpensesPagePlaywrightTest`
- planned and multi-currency sections in `IncomesPagePlaywrightTest`

## 12. Toshl CSV Amount Conversion

### Business rules

Toshl major-unit decimal amounts are converted exactly according to row currency:

```text
minor = major × 10^currencyFractionDigits
```

Import does not round. Any amount that cannot be represented exactly as integer minor units is invalid. Malformed, unsupported-currency, over-precise, or out-of-`Long` values produce `CSV_INVALID`; the import transaction creates no partial data.

Exactly one of expense amount and income amount must be positive.

### Examples

```text
AUD 104.00 -> 10400
JPY 104    -> 104
KWD 1.234  -> 1234
AUD 1.001  -> invalid (fraction of a cent)
```

### Edge cases

- Grouping commas in quoted Toshl values are supported.
- Blank amount fields mean zero.
- Negative, both-positive, and both-zero rows are invalid.
- Pseudo-currencies without a nonnegative fraction-digit definition are invalid.

### Coverage

- `ToshlImportApiTest`

## 13. Toshl Account Currency Inference

### Business rules

Only non-transfer income/expense rows establish the currency of a missing account. This includes reconciliation adjustment rows, which still carry an income/expense direction. Transfer rows do not create accounts or infer account currency.

All ordinary rows for one account name must use the same currency. An existing account must match that currency. Conflicts reject the entire import before writes, making the result independent of CSV row order.

### Example

```text
Cash expense row: AUD
Cash income row:  EUR
result: CSV_INVALID, no Cash account or transactions created
```

### Edge cases

- Account-name matching follows the import's exact-name policy.
- A transfer-only account remains unknown and its transfer is reported unmatched.
- A reconciliation adjustment row may establish a missing account currency, but it must agree with every other non-transfer row for that account.

### Coverage

- account-currency cases in `ToshlImportApiTest`

## 14. Toshl Transfer Reconciliation and Amount Selection

### Business rules

Transfer-category rows are paired using:

```text
date + original row amount + original row currency
```

The pair must contain an expense side and income side on different accounts.

For each saved side:

```text
if row currency == account currency:
    use original row amount
else if Toshl main currency == account currency and main amount > 0:
    use Toshl main-currency amount
else:
    unmatched
```

Unmatched rows create no transfer and appear in warnings and the processing report.

### Example

An EUR source row is EUR 100.00. Its AUD target row reports original EUR 100.00 and Toshl main amount AUD 170.00:

```text
source amount = EUR 100.00 (10000)
target amount = AUD 170.00 (17000)
```

### Edge cases

- Same-account candidates are excluded; later compatible candidates are still considered.
- Zero/blank conversion amounts cannot create a transfer.
- Ambiguous equal candidates are paired deterministically by input order.
- Transfer duplicate identity is date, source account, target account, source amount, and target amount.

### Coverage

- transfer reconciliation cases in `ToshlImportApiTest`

## 15. Toshl Reconciliation Adjustments

### Business rules

Toshl reconciliation rows become account adjustments:

```text
expense reconciliation -> negative adjustment
income reconciliation  -> positive adjustment
```

Adjustment duplicate identity includes account, date, and signed amount.

### Example

```text
2026-01-01 expense reconciliation 10000 -> adjustment -10000
2026-02-01 expense reconciliation 10000 -> separate adjustment -10000
```

### Edge cases

- The same amount on different dates is not a duplicate.
- Future reconciliation adjustments are stored but excluded from current balances until their date.
- Imported adjustment signs are based on row type, never on a negative stored amount field.

### Coverage

- reconciliation adjustment cases in `ToshlImportApiTest`
- as-of-today behavior in `AccountAdjustmentApiTest` and `DashboardApiTest`

## 16. Toshl Transaction Duplicate Identity and Counters

### Business rules

An imported income/expense duplicate key is:

```text
type + date + amountMinor + final notes/description
```

Final notes include appended Toshl tags. Account and category are intentionally not part of the key.

Each source row receives one processing-report status and reason. Imported, duplicate, and unmatched counters are derived from those outcomes.

### Edge cases

- Same date/type/amount with different descriptions are distinct.
- Same key on another account is still a duplicate under the product's import identity policy.
- Duplicate detection is scoped to the current user.

### Coverage

- duplicate and report cases in `ToshlImportApiTest`

## 17. Downloaded Toshl Report Amounts

### Business rules

The processing report formats normalized backend minor units using each row's currency, then CSV-escapes the resulting text. It reports normalized values rather than preserving the source spelling.

### Example

```text
amountMinor = 1234, currency = AUD
Amount column = A$12.34 (subject to browser locale symbol placement)
Currency column = AUD
```

### Edge cases

- Localized grouping or decimal separators cause the CSV field to be quoted when required.
- The separate currency column remains authoritative when symbols are ambiguous.

### Coverage

- import report API contents in `ToshlImportApiTest`
- download availability in `SettingsPagePlaywrightTest`

## 18. Exact Arithmetic Policy

### Business rules

Backend monetary calculations use signed 64-bit integers. Addition and subtraction use exact operations:

```text
Math.addExact
Math.subtractExact
```

Crossing `Long.MAX_VALUE` or `Long.MIN_VALUE` is an error. Returning a wrapped balance is forbidden.

Frontend calculations must remain within JavaScript's safe-integer range. Values outside that range are rejected rather than approximately represented.

### Examples

```text
Long.MAX_VALUE + 0 = Long.MAX_VALUE
Long.MAX_VALUE + 1 = error
Long.MIN_VALUE - 1 = error
```

### Coverage

- `FinancialMathTest`
- overflow-related parser tests in `ui/src/utils/money.test.ts`
- Toshl out-of-range tests in `ToshlImportApiTest`
