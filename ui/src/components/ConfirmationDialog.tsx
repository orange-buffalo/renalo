import {
  Dialog,
  Modal,
  ModalOverlay,
} from "@/components/untitled/application/modals/modal";
import { Button } from "@/components/untitled/base/buttons/button";

type ConfirmationDialogProps = {
  isOpen: boolean;
  title: string;
  description: string;
  confirmLabel: string;
  isConfirming?: boolean;
  dataTestId?: string;
  onCancel: () => void;
  onConfirm: () => void;
};

export function ConfirmationDialog({
  isOpen,
  title,
  description,
  confirmLabel,
  isConfirming,
  dataTestId,
  onCancel,
  onConfirm,
}: ConfirmationDialogProps) {
  return (
    <ModalOverlay
      data-testid={dataTestId}
      isOpen={isOpen}
      isDismissable
      className={(state) => (state.isExiting ? "hidden" : "")}
      onOpenChange={(nextIsOpen) => {
        if (!nextIsOpen) {
          onCancel();
        }
      }}
    >
      <Modal className="w-full max-w-md">
        <Dialog aria-label={title}>
          <div className="p-6">
            <h2 className="m-0 text-lg font-semibold text-primary">{title}</h2>
            <p className="mt-2 mb-0 text-sm text-tertiary">{description}</p>
          </div>
          <div className="flex justify-between gap-3 border-t border-secondary px-6 py-4">
            <Button
              color="tertiary"
              size="sm"
              onPress={onCancel}
              isDisabled={isConfirming}
            >
              Cancel
            </Button>
            <Button
              color="primary-destructive"
              size="sm"
              onPress={onConfirm}
              isLoading={isConfirming}
            >
              {confirmLabel}
            </Button>
          </div>
        </Dialog>
      </Modal>
    </ModalOverlay>
  );
}
