import type { ButtonHTMLAttributes, ReactElement, ReactNode } from "react";

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  children: ReactNode;
  color?:
    | "primary"
    | "secondary"
    | "tertiary"
    | "primary-destructive"
    | "secondary-destructive"
    | "tertiary-destructive"
    | "link-gray"
    | "link-color"
    | "link-destructive";
  iconLeading?: ReactElement;
  iconTrailing?: ReactElement;
  isDisabled?: boolean;
  isLoading?: boolean;
  showTextWhileLoading?: boolean;
  size?: "xs" | "sm" | "md" | "lg" | "xl";
};

// Local copy-style primitive following Untitled UI's documented Button API.
export function Button({
  children,
  className,
  color = "primary",
  iconLeading,
  iconTrailing,
  isDisabled,
  isLoading,
  showTextWhileLoading = true,
  size = "sm",
  ...props
}: ButtonProps) {
  const text = !isLoading || showTextWhileLoading ? children : null;

  return (
    <button
      className={[
        "ui-button",
        `ui-button--${color}`,
        `ui-button--${size}`,
        className,
      ]
        .filter(Boolean)
        .join(" ")}
      disabled={isDisabled || isLoading}
      {...props}
    >
      {isLoading ? (
        <span className="ui-button__spinner" aria-hidden="true" />
      ) : (
        iconLeading
      )}
      {text ? <span>{text}</span> : null}
      {iconTrailing}
    </button>
  );
}
