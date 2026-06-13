import type { InputHTMLAttributes } from "react";

type TextFieldProps = InputHTMLAttributes<HTMLInputElement> & {
  label: string;
  name: string;
};

// Local Untitled UI-style primitive, adapted for this app from https://github.com/untitleduico/react.
export function TextField({
  label,
  name,
  type = "text",
  ...props
}: TextFieldProps) {
  return (
    <label className="ui-field">
      <span>{label}</span>
      <input name={name} type={type} {...props} />
    </label>
  );
}
