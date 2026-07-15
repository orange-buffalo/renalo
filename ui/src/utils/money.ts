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
  assertSafeMinorUnits(minorUnits);
  const fractionDigits = currencyFractionDigits(currency);
  const formatter = new Intl.NumberFormat(undefined, {
    style: "currency",
    currency,
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  });
  return formatExactDecimal(
    formatter,
    minorUnitsToDecimal(minorUnits, fractionDigits),
  );
}

export function formatMoneyInput(minorUnits: number, currency: string) {
  assertSafeMinorUnits(minorUnits);
  const fractionDigits = currencyFractionDigits(currency);
  const formatter = new Intl.NumberFormat(undefined, {
    minimumFractionDigits: fractionDigits,
    maximumFractionDigits: fractionDigits,
  });
  return formatExactDecimal(
    formatter,
    minorUnitsToDecimal(minorUnits, fractionDigits),
  );
}

export function parseMoneyInput(value: string, currency: string) {
  const fractionDigits = currencyFractionDigits(currency);
  const syntax = localeNumberSyntax();
  let unsigned = value.trim();
  let isNegative = false;

  if (unsigned.startsWith(syntax.minusSign)) {
    isNegative = true;
    unsigned = unsigned.slice(syntax.minusSign.length);
  }

  const decimalParts = unsigned.split(syntax.decimalSeparator);
  if (decimalParts.length > 2) {
    return undefined;
  }

  const [localizedInteger, localizedFraction] = decimalParts;
  if (
    localizedInteger === "" ||
    (decimalParts.length === 2 && localizedFraction === "") ||
    localizedFraction?.includes(syntax.groupSeparator)
  ) {
    return undefined;
  }

  const integer = normalizeGroupedInteger(localizedInteger, syntax);
  const fraction = normalizeDigits(localizedFraction ?? "", syntax.digits);
  if (
    integer === undefined ||
    fraction === undefined ||
    (integer.length > 1 && integer.startsWith("0"))
  ) {
    return undefined;
  }

  const retainedFraction = fraction
    .slice(0, fractionDigits)
    .padEnd(fractionDigits, "0");
  let minorUnits = BigInt(`${integer}${retainedFraction}`);

  // Excess precision is rounded to the nearest minor unit, with ties away
  // from zero. String arithmetic avoids binary floating-point tie errors.
  if (fraction.length > fractionDigits && fraction[fractionDigits] >= "5") {
    minorUnits += 1n;
  }
  if (isNegative) {
    minorUnits = -minorUnits;
  }

  const parsed = Number(minorUnits);
  return Number.isSafeInteger(parsed) ? parsed : undefined;
}

type LocaleNumberSyntax = {
  decimalSeparator: string;
  groupSeparator: string;
  minusSign: string;
  digits: Map<string, string>;
  primaryGroupSize: number;
  secondaryGroupSize: number;
};

function localeNumberSyntax(): LocaleNumberSyntax {
  const formatter = new Intl.NumberFormat(undefined);
  const decimalSeparator =
    formatter.formatToParts(1.1).find((part) => part.type === "decimal")
      ?.value ?? ".";
  const minusSign =
    formatter.formatToParts(-1).find((part) => part.type === "minusSign")
      ?.value ?? "-";
  const groupedParts = formatter.formatToParts(1234567890123);
  const integerGroups = groupedParts
    .filter((part) => part.type === "integer")
    .map((part) => Array.from(part.value).length);
  const groupSeparator =
    groupedParts.find((part) => part.type === "group")?.value ?? ",";
  const primaryGroupSize = integerGroups.at(-1) ?? 3;
  const secondaryGroupSize = integerGroups.at(-2) ?? primaryGroupSize;
  const digits = new Map<string, string>();

  for (let digit = 0; digit <= 9; digit += 1) {
    digits.set(
      new Intl.NumberFormat(undefined, { useGrouping: false }).format(digit),
      String(digit),
    );
  }

  return {
    decimalSeparator,
    groupSeparator,
    minusSign,
    digits,
    primaryGroupSize,
    secondaryGroupSize,
  };
}

function normalizeGroupedInteger(value: string, syntax: LocaleNumberSyntax) {
  const groups = value.split(syntax.groupSeparator);
  if (groups.some((group) => group === "")) {
    return undefined;
  }

  const normalizedGroups = groups.map((group) =>
    normalizeDigits(group, syntax.digits),
  );
  if (normalizedGroups.some((group) => group === undefined)) {
    return undefined;
  }

  if (groups.length > 1) {
    const firstGroupLength = Array.from(groups[0]).length;
    if (
      firstGroupLength < 1 ||
      firstGroupLength > syntax.secondaryGroupSize ||
      Array.from(groups.at(-1) ?? "").length !== syntax.primaryGroupSize ||
      groups
        .slice(1, -1)
        .some((group) => Array.from(group).length !== syntax.secondaryGroupSize)
    ) {
      return undefined;
    }
  }

  return normalizedGroups.join("");
}

function normalizeDigits(value: string, digits: Map<string, string>) {
  let normalized = "";
  for (const digit of Array.from(value)) {
    const asciiDigit = digits.get(digit);
    if (asciiDigit === undefined) {
      return undefined;
    }
    normalized += asciiDigit;
  }
  return normalized;
}

function assertSafeMinorUnits(minorUnits: number) {
  if (!Number.isSafeInteger(minorUnits)) {
    throw new RangeError("Minor units must be a safe integer");
  }
}

function minorUnitsToDecimal(minorUnits: number, fractionDigits: number) {
  const sign = minorUnits < 0 ? "-" : "";
  const digits = String(Math.abs(minorUnits)).padStart(fractionDigits + 1, "0");
  if (fractionDigits === 0) {
    return `${sign}${digits}`;
  }
  return `${sign}${digits.slice(0, -fractionDigits)}.${digits.slice(-fractionDigits)}`;
}

function formatExactDecimal(formatter: Intl.NumberFormat, value: string) {
  // ECMA-402 accepts decimal strings and preserves their exact mathematical
  // value, unlike converting safe minor units to a floating-point major value.
  return formatter.format(value as unknown as number);
}
