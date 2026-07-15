// @ts-expect-error Bun provides this module to its test runner.
import { describe, expect, test } from "bun:test";
import {
  currencyFractionDigits,
  formatMoney,
  formatMoneyInput,
  parseMoneyInput,
} from "./money";

const decimalSeparator =
  new Intl.NumberFormat(undefined)
    .formatToParts(1.1)
    .find((part) => part.type === "decimal")?.value ?? ".";
const groupSeparator =
  new Intl.NumberFormat(undefined)
    .formatToParts(1000)
    .find((part) => part.type === "group")?.value ?? ",";
const minusSign =
  new Intl.NumberFormat(undefined)
    .formatToParts(-1)
    .find((part) => part.type === "minusSign")?.value ?? "-";

function localized(value: string) {
  return value.replace("-", minusSign).replace(".", decimalSeparator);
}

describe("currencyFractionDigits", () => {
  test("uses ISO currency precision", () => {
    expect(currencyFractionDigits("JPY")).toBe(0);
    expect(currencyFractionDigits("AUD")).toBe(2);
    expect(currencyFractionDigits("KWD")).toBe(3);
  });
});

describe("formatMoney", () => {
  test("preserves normal AUD formatting", () => {
    const expected = new Intl.NumberFormat(undefined, {
      style: "currency",
      currency: "AUD",
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(123.45);

    expect(formatMoney(12345, "AUD")).toBe(expected);
  });

  test("formats zero, negative values, and each supported precision", () => {
    expect(formatMoneyInput(0, "JPY")).toBe(
      new Intl.NumberFormat(undefined, {
        minimumFractionDigits: 0,
        maximumFractionDigits: 0,
      }).format(0),
    );
    expect(formatMoneyInput(-12345, "AUD")).toBe(
      new Intl.NumberFormat(undefined, {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
      }).format(-123.45),
    );
    expect(formatMoneyInput(1234, "KWD")).toBe(
      new Intl.NumberFormat(undefined, {
        minimumFractionDigits: 3,
        maximumFractionDigits: 3,
      }).format(1.234),
    );
  });

  test("formats safe-integer boundaries without losing minor units", () => {
    const formatter = new Intl.NumberFormat(undefined, {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });

    expect(formatMoneyInput(Number.MAX_SAFE_INTEGER, "AUD")).toBe(
      formatter.format("90071992547409.91" as unknown as number),
    );
    expect(formatMoneyInput(Number.MIN_SAFE_INTEGER, "AUD")).toBe(
      formatter.format("-90071992547409.91" as unknown as number),
    );
  });

  test.each([
    Number.NaN,
    Number.POSITIVE_INFINITY,
    Number.NEGATIVE_INFINITY,
    1.5,
    Number.MAX_SAFE_INTEGER + 1,
    Number.MIN_SAFE_INTEGER - 1,
  ])("rejects non-safe minor units: %s", (minorUnits: number) => {
    expect(() => formatMoney(minorUnits, "AUD")).toThrow(
      new RangeError("Minor units must be a safe integer"),
    );
    expect(() => formatMoneyInput(minorUnits, "AUD")).toThrow(
      new RangeError("Minor units must be a safe integer"),
    );
  });
});

describe("parseMoneyInput", () => {
  test("parses normal, zero, negative, and abbreviated fractions", () => {
    expect(parseMoneyInput(localized("123.45"), "AUD")).toBe(12345);
    expect(parseMoneyInput("0", "AUD")).toBe(0);
    expect(parseMoneyInput(localized("-0.01"), "AUD")).toBe(-1);
    expect(parseMoneyInput(localized("12.3"), "AUD")).toBe(1230);
    expect(parseMoneyInput("12", "AUD")).toBe(1200);
  });

  test("parses locale-formatted grouping and localized negative values", () => {
    const positive = formatMoneyInput(123456789, "AUD");
    const negative = formatMoneyInput(-123456789, "AUD");

    expect(positive).toContain(groupSeparator);
    expect(parseMoneyInput(positive, "AUD")).toBe(123456789);
    expect(parseMoneyInput(negative, "AUD")).toBe(-123456789);
  });

  test("supports zero-, two-, and three-digit currencies", () => {
    expect(parseMoneyInput("123", "JPY")).toBe(123);
    expect(parseMoneyInput(localized("1.23"), "AUD")).toBe(123);
    expect(parseMoneyInput(localized("1.234"), "KWD")).toBe(1234);
  });

  test("rounds excess precision to nearest minor unit, ties away from zero", () => {
    expect(parseMoneyInput(localized("1.004"), "AUD")).toBe(100);
    expect(parseMoneyInput(localized("1.005"), "AUD")).toBe(101);
    expect(parseMoneyInput(localized("-1.005"), "AUD")).toBe(-101);
    expect(parseMoneyInput(localized("1.2344"), "KWD")).toBe(1234);
    expect(parseMoneyInput(localized("1.2345"), "KWD")).toBe(1235);
    expect(parseMoneyInput(localized("1.5"), "JPY")).toBe(2);
    expect(parseMoneyInput(localized("-1.5"), "JPY")).toBe(-2);
  });

  test("accepts exact safe-integer boundaries and rejects rounded overflow", () => {
    expect(parseMoneyInput(localized("90071992547409.91"), "AUD")).toBe(
      Number.MAX_SAFE_INTEGER,
    );
    expect(parseMoneyInput(localized("-90071992547409.91"), "AUD")).toBe(
      Number.MIN_SAFE_INTEGER,
    );
    expect(parseMoneyInput(localized("90071992547409.914"), "AUD")).toBe(
      Number.MAX_SAFE_INTEGER,
    );
    expect(
      parseMoneyInput(localized("90071992547409.915"), "AUD"),
    ).toBeUndefined();
    expect(
      parseMoneyInput(localized("-90071992547409.915"), "AUD"),
    ).toBeUndefined();
  });

  test.each([
    "",
    "   ",
    "-",
    "+1",
    ".5",
    "1.",
    "1..2",
    "01",
    "1e2",
    "$1.00",
    "AUD 1.00",
    "1 000",
    "--1",
    "1-2",
  ])("rejects malformed or permissive input: %s", (value: string) => {
    expect(parseMoneyInput(localized(value), "AUD")).toBeUndefined();
  });

  test("rejects malformed locale grouping", () => {
    expect(
      parseMoneyInput(`1${groupSeparator}00${decimalSeparator}00`, "AUD"),
    ).toBeUndefined();
    expect(
      parseMoneyInput(`1${groupSeparator}${groupSeparator}000`, "AUD"),
    ).toBeUndefined();
    expect(
      parseMoneyInput(`12${groupSeparator}34${decimalSeparator}56`, "AUD"),
    ).toBeUndefined();
  });
});
