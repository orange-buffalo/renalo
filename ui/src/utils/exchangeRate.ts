import { currencyFractionDigits } from "@/utils/money";

export function parseExchangeRate(value: string) {
  const normalized = normalizeRate(value);
  if (!normalized) return undefined;
  const parsed = Number(normalized);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
}

export function formatExchangeRate(
  sourceAmountMinor: number,
  sourceCurrency: string,
  targetAmountMinor: number,
  targetCurrency: string,
) {
  if (sourceAmountMinor <= 0 || targetAmountMinor <= 0) return "";
  assertSafeMinorUnits(sourceAmountMinor);
  assertSafeMinorUnits(targetAmountMinor);
  const numerator =
    BigInt(targetAmountMinor) *
    10n ** BigInt(currencyFractionDigits(sourceCurrency));
  const denominator =
    BigInt(sourceAmountMinor) *
    10n ** BigInt(currencyFractionDigits(targetCurrency));
  return divideToDecimal(numerator, denominator, 20);
}

export function calculateTargetAmountMinor(
  sourceAmountMinor: number,
  sourceCurrency: string,
  targetCurrency: string,
  exchangeRate: string,
) {
  assertSafeMinorUnits(sourceAmountMinor);
  const normalized = normalizeRate(exchangeRate);
  if (!normalized) return undefined;
  const [whole, fraction = ""] = normalized.split(".");
  const rateNumerator = BigInt(`${whole}${fraction}`);
  if (rateNumerator <= 0n) return undefined;

  const numerator =
    BigInt(sourceAmountMinor) *
    rateNumerator *
    10n ** BigInt(currencyFractionDigits(targetCurrency));
  const denominator =
    10n ** BigInt(currencyFractionDigits(sourceCurrency) + fraction.length);
  const quotient = numerator / denominator;
  const remainder = numerator % denominator;
  const rounded = remainder * 2n >= denominator ? quotient + 1n : quotient;
  const result = Number(rounded);
  return Number.isSafeInteger(result) ? result : undefined;
}

function normalizeRate(value: string) {
  const normalized = value.trim().replace(",", ".");
  return /^\d+(?:\.\d+)?$/.test(normalized) ? normalized : undefined;
}

function divideToDecimal(
  numerator: bigint,
  denominator: bigint,
  maximumFractionDigits: number,
) {
  const whole = numerator / denominator;
  let remainder = numerator % denominator;
  if (remainder === 0n) return whole.toString();

  const digits: number[] = [];
  for (
    let index = 0;
    index < maximumFractionDigits + 1 && remainder !== 0n;
    index += 1
  ) {
    remainder *= 10n;
    digits.push(Number(remainder / denominator));
    remainder %= denominator;
  }

  const roundingDigit = digits[maximumFractionDigits] ?? 0;
  const retained = digits.slice(0, maximumFractionDigits);
  if (roundingDigit >= 5) {
    for (let index = retained.length - 1; index >= 0; index -= 1) {
      if (retained[index] < 9) {
        retained[index] += 1;
        break;
      }
      retained[index] = 0;
      if (index === 0) return (whole + 1n).toString();
    }
  }

  const fraction = retained.join("").replace(/0+$/, "");
  return fraction ? `${whole}.${fraction}` : whole.toString();
}

function assertSafeMinorUnits(value: number) {
  if (!Number.isSafeInteger(value))
    throw new RangeError("Minor units must be a safe integer");
}
