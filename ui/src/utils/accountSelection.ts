export function loadStoredAccountId(storageKey: string): number | undefined {
  try {
    const storedValue = window.localStorage.getItem(storageKey);
    if (!storedValue) {
      return undefined;
    }

    const parsedValue = JSON.parse(storedValue) as unknown;
    if (typeof parsedValue !== "number" || !Number.isInteger(parsedValue)) {
      return undefined;
    }

    return parsedValue;
  } catch {
    return undefined;
  }
}

export function storeAccountId(storageKey: string, accountId: number) {
  try {
    window.localStorage.setItem(storageKey, JSON.stringify(accountId));
  } catch {
    // Ignore storage errors
  }
}
