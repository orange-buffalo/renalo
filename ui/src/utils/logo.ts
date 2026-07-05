export function logoUrl(): string {
  const meta = document.querySelector('meta[name="logo-url"]');
  return meta?.getAttribute("content") ?? "/assets/logo.svg";
}
