// @ts-expect-error Bun provides this module to its test runner.
import { describe, expect, test } from "bun:test";
import {
  calculateTargetAmountMinor,
  formatExchangeRate,
  parseExchangeRate,
} from "./exchangeRate";

describe("parseExchangeRate", () => {
  test("accepts positive decimal rates with either decimal separator", () => {
    expect(parseExchangeRate("0.5")).toBe(0.5);
    expect(parseExchangeRate(" 1,25 ")).toBe(1.25);
  });

  test.each(["", "0", "-1", "1e-3", "1,234.56", "1.2.3", "rate 2"])(
    "rejects invalid rate %s",
    (rate: string) => expect(parseExchangeRate(rate)).toBeUndefined(),
  );
});

describe("calculateTargetAmountMinor", () => {
  test("uses the fraction digits of both currencies", () => {
    expect(calculateTargetAmountMinor(10_000, "AUD", "EUR", "0.5")).toBe(5_000);
    expect(calculateTargetAmountMinor(10_000, "JPY", "KWD", "0.001234")).toBe(
      12_340,
    );
  });

  test("rounds half of a target minor unit upward", () => {
    expect(calculateTargetAmountMinor(1, "JPY", "AUD", "0.0049")).toBe(0);
    expect(calculateTargetAmountMinor(1, "JPY", "AUD", "0.005")).toBe(1);
  });

  test("rejects malformed and unsafe results", () => {
    expect(
      calculateTargetAmountMinor(100, "AUD", "EUR", "bad"),
    ).toBeUndefined();
    expect(
      calculateTargetAmountMinor(
        Number.MAX_SAFE_INTEGER,
        "JPY",
        "KWD",
        "999999",
      ),
    ).toBeUndefined();
  });
});

describe("formatExchangeRate", () => {
  test("formats exact and repeating implied rates", () => {
    expect(formatExchangeRate(10_000, "AUD", 5_000, "EUR")).toBe("0.5");
    expect(formatExchangeRate(3, "JPY", 1, "JPY")).toBe(
      "0.33333333333333333333",
    );
  });

  test("does not turn tiny positive rates into zero", () => {
    expect(formatExchangeRate(1_000_000_000, "JPY", 1, "JPY")).toBe(
      "0.000000001",
    );
  });

  test("returns blank for nonpositive amounts", () => {
    expect(formatExchangeRate(0, "AUD", 1, "EUR")).toBe("");
    expect(formatExchangeRate(1, "AUD", 0, "EUR")).toBe("");
  });
});
