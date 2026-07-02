"use client";

import { File04, Trash01, UploadCloud01 } from "@untitledui/icons";
import type { DragEvent, ReactNode } from "react";
import { useRef, useState } from "react";
import { Button } from "@/components/untitled/base/buttons/button";
import { InputBase } from "@/components/untitled/base/input/input";
import { InputGroup } from "@/components/untitled/base/input/input-group";
import { cx } from "@/utils/cx";

interface InputFileProps {
  /**
   * The size of the input.
   * @default "sm"
   */
  size?: "sm" | "md" | "lg";
  /** Label text for the input. */
  label?: string;
  /** Helper text displayed below the input. */
  hint?: ReactNode;
  /** Placeholder text displayed when no file is selected. */
  placeholder?: string;
  /** Whether the input is disabled. */
  isDisabled?: boolean;
  /** Whether the input is invalid. */
  isInvalid?: boolean;
  /** Whether the input is required. */
  isRequired?: boolean;
  /** Whether to hide the required indicator from the label. */
  hideRequiredIndicator?: boolean;
  /** Specifies what mime type of files are allowed. */
  acceptedFileTypes?: string[];
  /** Whether multiple files can be selected. */
  allowsMultiple?: boolean;
  /** Visual style for the file input. */
  variant?: "input" | "dropzone";
  /** Whether the file is currently uploading. */
  isLoading?: boolean;
  /** Handler when a user selects files. */
  onChange?: (files: FileList | null) => void;
  /** The class name for the root element. */
  className?: string;
  /**
   * The text of the upload button.
   * @default "Upload"
   */
  buttonText?: string;
}

export const InputFile = ({
  size = "sm",
  label,
  hint,
  placeholder = "Choose a file",
  isDisabled,
  isInvalid,
  isRequired,
  hideRequiredIndicator,
  isLoading,
  acceptedFileTypes,
  allowsMultiple,
  variant = "input",
  onChange,
  className,
  buttonText = "Upload",
}: InputFileProps) => {
  const inputRef = useRef<HTMLInputElement>(null);
  const [fileNames, setFileNames] = useState("");
  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);

  const handleClick = () => {
    if (inputRef.current?.value) {
      inputRef.current.value = "";
    }
    inputRef.current?.click();
  };

  const updateFiles = (files: FileList | null) => {
    if (files && files.length > 0) {
      setSelectedFiles(Array.from(files));
      setFileNames(
        Array.from(files)
          .map((f) => f.name)
          .join(", "),
      );
    } else {
      setSelectedFiles([]);
      setFileNames("");
    }
    onChange?.(files);
  };

  const handleChange = () => {
    updateFiles(inputRef.current?.files ?? null);
  };

  const handleDrop = (event: DragEvent<HTMLButtonElement>) => {
    event.preventDefault();
    if (isDisabled) {
      return;
    }
    updateFiles(event.dataTransfer.files);
  };

  const clearFiles = () => {
    if (inputRef.current) {
      inputRef.current.value = "";
    }
    setSelectedFiles([]);
    setFileNames("");
    onChange?.(null);
  };

  const hiddenInput = (
    <input
      ref={inputRef}
      type="file"
      className="hidden"
      disabled={isDisabled}
      accept={acceptedFileTypes?.toString()}
      multiple={allowsMultiple}
      onChange={handleChange}
    />
  );

  if (variant === "dropzone") {
    return (
      <div className={cx("file-uploader", className)}>
        {label && <p className="file-uploader-label">{label}</p>}
        <button
          type="button"
          className={cx(
            "file-uploader-dropzone",
            isDisabled && "file-uploader-dropzone--disabled",
          )}
          disabled={isDisabled}
          onClick={handleClick}
          onDragOver={(event) => event.preventDefault()}
          onDrop={handleDrop}
        >
          <span className="file-uploader-icon">
            <UploadCloud01 aria-hidden="true" />
          </span>
          <span>
            <strong>{buttonText}</strong> or drag and drop
          </span>
          {hint && <span className="file-uploader-hint">{hint}</span>}
        </button>
        {selectedFiles.map((file) => (
          <div className="file-uploader-file" key={file.name}>
            <span className="file-uploader-file-icon">
              <File04 aria-hidden="true" />
            </span>
            <span className="file-uploader-file-details">
              <strong>{file.name}</strong>
              <span>{formatFileSize(file.size)}</span>
            </span>
            <button
              type="button"
              className="file-uploader-remove"
              aria-label={`Remove ${file.name}`}
              onClick={clearFiles}
            >
              <Trash01 aria-hidden="true" />
            </button>
          </div>
        ))}
        {hiddenInput}
      </div>
    );
  }

  return (
    <>
      <InputGroup
        size={size}
        label={label}
        hint={hint}
        isDisabled={isDisabled}
        isInvalid={isInvalid}
        isRequired={isRequired}
        hideRequiredIndicator={hideRequiredIndicator}
        className={className}
        trailingAddon={
          <Button
            size={size}
            color="secondary"
            onClick={handleClick}
            isDisabled={isDisabled}
          >
            {buttonText}
          </Button>
        }
      >
        <div className="relative flex min-w-0 flex-1">
          <InputBase
            placeholder={placeholder}
            value={fileNames}
            readOnly
            inputClassName={cx("cursor-pointer", isLoading && "pr-9")}
            wrapperClassName="cursor-pointer"
            onClick={handleClick}
          />
          {isLoading && (
            <svg
              fill="none"
              viewBox="0 0 16 16"
              className="pointer-events-none absolute top-1/2 right-3 z-20 size-4 -translate-y-1/2 text-fg-quaternary"
            >
              <circle
                className="stroke-current opacity-30"
                cx="8"
                cy="8"
                r="6.5"
                strokeWidth="1.5"
              />
              <circle
                className="origin-center animate-spin stroke-current"
                cx="8"
                cy="8"
                r="6.5"
                strokeWidth="1.5"
                strokeDasharray="10 40"
                strokeLinecap="round"
              />
            </svg>
          )}
        </div>
      </InputGroup>

      {hiddenInput}
    </>
  );
};

InputFile.displayName = "InputFile";

function formatFileSize(size: number): string {
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${Math.round(size / 1024)} KB`;
  }
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}
