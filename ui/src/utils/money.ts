const fallbackCurrencies = [
  "AUD",
  "CAD",
  "CHF",
  "CNY",
  "EUR",
  "GBP",
  "JPY",
  "NZD",
  "USD",
];

export type CurrencyOption = {
  id: string;
  label: string;
  supportingText: string;
};

export function getCurrencyOptions(): CurrencyOption[] {
  const codes =
    typeof (
      Intl as unknown as { supportedValuesOf?: (key: string) => string[] }
    ).supportedValuesOf === "function"
      ? (
          Intl as unknown as { supportedValuesOf: (key: string) => string[] }
        ).supportedValuesOf("currency")
      : fallbackCurrencies;
  const displayNames =
    typeof Intl.DisplayNames === "function"
      ? new Intl.DisplayNames(undefined, { type: "currency" })
      : undefined;

  return codes
    .map((code) => ({
      id: code,
      label: `${displayNames?.of(code) ?? code} -`,
      supportingText: code,
    }))
    .sort((left, right) =>
      `${left.label} ${left.supportingText}`.localeCompare(
        `${right.label} ${right.supportingText}`,
      ),
    );
}

export function currencyFractionDigits(currency: string) {
  return (
    new Intl.NumberFormat(undefined, {
      style: "currency",
      currency,
    }).resolvedOptions().maximumFractionDigits ?? 0
  );
}

export function formatMoney(minorUnits: number, currency: string) {
  const fractionDigits = currencyFractionDigits(currency);
  return new Intl.NumberFormat(undefined, {
    style: "currency",
    currency,
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  }).format(minorUnits / 10 ** fractionDigits);
}

export function formatMoneyInput(minorUnits: number, currency: string) {
  const fractionDigits = currencyFractionDigits(currency);
  return new Intl.NumberFormat(undefined, {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  }).format(minorUnits / 10 ** fractionDigits);
}

export function parseMoneyInput(value: string, currency: string) {
  const fractionDigits = currencyFractionDigits(currency);
  const decimalSeparator = new Intl.NumberFormat(undefined)
    .formatToParts(1.1)
    .find((part) => part.type === "decimal")?.value;
  const normalized = value
    .trim()
    .replace(
      new RegExp(`[^0-9${escapeRegExp(decimalSeparator ?? ".")}-]`, "g"),
      "",
    )
    .replace(decimalSeparator ?? ".", ".");
  if (
    normalized === "" ||
    normalized === "-" ||
    Number.isNaN(Number(normalized))
  ) {
    return undefined;
  }

  return Math.round(Number(normalized) * 10 ** fractionDigits);
}

function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
