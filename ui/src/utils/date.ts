export function formatDateDisplay(isoDate: string) {
  const date = new Date(isoDate);
  return new Intl.DateTimeFormat("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
  }).format(date);
}
