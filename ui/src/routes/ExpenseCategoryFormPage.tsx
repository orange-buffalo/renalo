import { type FormEvent, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router";
import {
  createExpenseCategory,
  type ExpenseCategory,
  fetchExpenseCategory,
  updateExpenseCategory,
} from "@/api/expenseCategories";
import { FormLoadingOverlay } from "@/components/FormLoadingOverlay";
import { PageLayout } from "@/components/PageLayout";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { Button } from "@/components/untitled/base/buttons/button";
import { Input } from "@/components/untitled/base/input/input";

export function CreateExpenseCategoryPage() {
  return <ExpenseCategoryFormPage mode="create" />;
}

export function EditExpenseCategoryPage() {
  return <ExpenseCategoryFormPage mode="edit" />;
}

function ExpenseCategoryFormPage({ mode }: { mode: "create" | "edit" }) {
  const navigate = useNavigate();
  const { categoryId } = useParams();
  const [category, setCategory] = useState<ExpenseCategory>();
  const [name, setName] = useState("");
  const [error, setError] = useState<string>();
  const [nameError, setNameError] = useState<string>();
  const [isLoading, setIsLoading] = useState(mode === "edit");
  const [isSubmitting, setIsSubmitting] = useState(false);

  const isEditing = mode === "edit";

  useEffect(() => {
    if (!isEditing || !categoryId) {
      return;
    }

    let isActive = true;
    fetchExpenseCategory(Number(categoryId))
      .then((loadedCategory) => {
        if (!isActive) {
          return;
        }
        setCategory(loadedCategory);
        setName(loadedCategory.name);
        setIsLoading(false);
      })
      .catch(() => {
        if (isActive) {
          setError(
            "Expense category could not be loaded. Try again in a moment.",
          );
          setIsLoading(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, [categoryId, isEditing]);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const nextNameError =
      name.trim() === "" ? "Enter an expense category name." : undefined;
    setNameError(nextNameError);
    if (nextNameError) {
      return;
    }

    setIsSubmitting(true);
    setError(undefined);

    try {
      if (isEditing) {
        await updateExpenseCategory(Number(categoryId), { name });
      } else {
        await createExpenseCategory({ name });
      }
      navigate("/settings?tab=expense-categories", { replace: true });
    } catch {
      setError("Expense category could not be saved. Try again in a moment.");
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <PageLayout
      eyebrow="Settings"
      title={
        isEditing
          ? `Edit ${category?.name ?? "category"}`
          : "Add new expense category"
      }
      description="Expense categories group spending for tracking and analytics."
    >
      <section className="standard-page-panel tracking-account-panel form-loading-container">
        <form className="tracking-account-form" onSubmit={handleSubmit}>
          {error && (
            <Alert
              tone="error"
              title={error}
              className="tracking-account-form-wide"
            />
          )}
          <Input
            label="Name"
            name="name"
            size="md"
            value={name}
            isRequired
            validationBehavior="aria"
            isInvalid={Boolean(nameError)}
            hint={nameError}
            onChange={(nextName) => {
              setName(nextName);
              setNameError(undefined);
            }}
          />
          <div aria-hidden="true" className="tracking-account-form-spacer" />
          <div className="tracking-account-actions">
            <Button
              color="tertiary"
              size="sm"
              onPress={() => navigate("/settings?tab=expense-categories")}
              isDisabled={isSubmitting}
            >
              Cancel
            </Button>
            <Button
              color="primary"
              size="sm"
              type="submit"
              isLoading={isSubmitting}
            >
              {isEditing ? "Save category" : "Create category"}
            </Button>
          </div>
        </form>
        <FormLoadingOverlay isLoading={isLoading} label="Loading category..." />
      </section>
    </PageLayout>
  );
}
