import { type FormEvent, useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router";
import {
  createIncomeCategory,
  fetchIncomeCategory,
  type IncomeCategory,
  updateIncomeCategory,
} from "@/api/incomeCategories";
import { FormLoadingOverlay } from "@/components/FormLoadingOverlay";
import { PageLayout } from "@/components/PageLayout";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { Button } from "@/components/untitled/base/buttons/button";
import { Input } from "@/components/untitled/base/input/input";

export function CreateIncomeCategoryPage() {
  return <IncomeCategoryFormPage mode="create" />;
}

export function EditIncomeCategoryPage() {
  return <IncomeCategoryFormPage mode="edit" />;
}

function IncomeCategoryFormPage({ mode }: { mode: "create" | "edit" }) {
  const navigate = useNavigate();
  const { categoryId } = useParams();
  const [category, setCategory] = useState<IncomeCategory>();
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
    fetchIncomeCategory(Number(categoryId))
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
            "Income category could not be loaded. Try again in a moment.",
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
      name.trim() === "" ? "Enter an income category name." : undefined;
    setNameError(nextNameError);
    if (nextNameError) {
      return;
    }

    setIsSubmitting(true);
    setError(undefined);

    try {
      if (isEditing) {
        await updateIncomeCategory(Number(categoryId), { name });
      } else {
        await createIncomeCategory({ name });
      }
      navigate("/settings?tab=income-categories", { replace: true });
    } catch {
      setError("Income category could not be saved. Try again in a moment.");
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
          : "Add new income category"
      }
      description="Income categories group earnings for tracking and analytics."
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
              onPress={() => navigate("/settings?tab=income-categories")}
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
        <FormLoadingOverlay isLoading={isLoading} />
      </section>
    </PageLayout>
  );
}
