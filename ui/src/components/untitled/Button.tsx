import type { ButtonHTMLAttributes, ReactNode } from "react";

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  children: ReactNode;
};

// Local Untitled UI-style primitive, adapted for this app from https://github.com/untitleduico/react.
export function Button({ children, className, ...props }: ButtonProps) {
  return (
    <button
      className={["ui-button", className].filter(Boolean).join(" ")}
      {...props}
    >
      {children}
    </button>
  );
}
