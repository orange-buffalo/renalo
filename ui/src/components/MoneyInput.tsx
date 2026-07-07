import { InputBase } from "@/components/untitled/base/input/input";
import { InputGroup } from "@/components/untitled/base/input/input-group";
import { formatMoneyInput, parseMoneyInput } from "@/utils/money";

type MoneyInputProps = {
  label: string;
  name: string;
  value: string;
  currency: string;
  hint?: string;
  isRequired?: boolean;
  isInvalid?: boolean;
  onChange: (value: string) => void;
};

export function MoneyInput({
  label,
  name,
  value,
  currency,
  hint,
  isRequired,
  isInvalid,
  onChange,
}: MoneyInputProps) {
  return (
    <InputGroup
      className="w-full money-input-field"
      label={label}
      isRequired={isRequired}
      validationBehavior="aria"
      isInvalid={isInvalid}
      hint={hint}
      trailingAddon={
        <InputGroup.Prefix className="money-input-currency-addon">
          {currency}
        </InputGroup.Prefix>
      }
    >
      <InputBase
        name={name}
        value={value}
        inputMode="decimal"
        inputClassName="text-right"
        onChange={(event) => onChange(event.target.value)}
        onBlur={() => {
          const parsedAmount = parseMoneyInput(value, currency);
          if (parsedAmount !== undefined) {
            onChange(formatMoneyInput(parsedAmount, currency));
          }
        }}
      />
    </InputGroup>
  );
}
