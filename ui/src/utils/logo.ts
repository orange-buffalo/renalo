export function logoUrl(): string {
  const meta = document.querySelector('meta[name="logo-url"]');
  return meta?.getAttribute("content") ?? "/assets/logo.svg";
}

export function logoExtendedUrl(): string {
  const meta = document.querySelector('meta[name="logo-extended-url"]');
  return meta?.getAttribute("content") ?? "/assets/logo-extended.svg";
}
