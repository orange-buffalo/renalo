import type { InputHTMLAttributes, ReactNode } from "react";

type TextFieldProps = Omit<InputHTMLAttributes<HTMLInputElement>, "size"> & {
  hint?: string;
  isInvalid?: boolean;
  label: string;
  name: string;
  size?: "sm" | "md";
  tooltip?: ReactNode;
};

// Local copy-style primitive following Untitled UI's documented Input API.
export function TextField({
  hint,
  isInvalid,
  label,
  name,
  size = "sm",
  tooltip,
  type = "text",
  ...props
}: TextFieldProps) {
  return (
    <div className={["ui-field", `ui-field--${size}`].join(" ")}>
      <label className="ui-field__label" htmlFor={name}>
        <span>{label}</span>
        {tooltip ? <span className="ui-field__tooltip">{tooltip}</span> : null}
      </label>
      <input
        aria-describedby={hint ? `${name}-hint` : undefined}
        aria-invalid={isInvalid || undefined}
        id={name}
        name={name}
        type={type}
        {...props}
      />
      {hint ? (
        <p className="ui-field__hint" id={`${name}-hint`}>
          {hint}
        </p>
      ) : null}
    </div>
  );
}
