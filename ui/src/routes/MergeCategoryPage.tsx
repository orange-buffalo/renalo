import { ChevronDown } from "@untitledui/icons";
import { type FormEvent, useEffect, useState } from "react";
import { Button as AriaButton } from "react-aria-components";
import { useNavigate, useParams } from "react-router";
import {
  type ExpenseCategory,
  type ExpenseCategoryMergeSummary,
  fetchExpenseCategoryMergeSummary,
  mergeExpenseCategory,
} from "@/api/expenseCategories";
import {
  fetchIncomeCategoryMergeSummary,
  type IncomeCategory,
  type IncomeCategoryMergeSummary,
  mergeIncomeCategory,
} from "@/api/incomeCategories";
import { FormLoadingOverlay } from "@/components/FormLoadingOverlay";
import { PageLayout } from "@/components/PageLayout";
import { Alert } from "@/components/untitled/application/alerts/alert";
import { Button } from "@/components/untitled/base/buttons/button";
import { Dropdown } from "@/components/untitled/base/dropdown/dropdown";
import { Label } from "@/components/untitled/base/input/label";

type CategoryKind = "expense" | "income";
type Category = ExpenseCategory | IncomeCategory;

type CategoryMergeSummary = {
  sourceCategory: Category;
  transactionsCount: number;
  targetCategories: Category[];
};

const categoryConfig = {
  expense: {
    pageDescription:
      "Move all expense entries from one category into another expense category.",
    countLabel: "Expenses",
    loadError:
      "Expense category merge details could not be loaded. Try again in a moment.",
    submitError:
      "Expense category could not be merged. Check the selected category and try again.",
    settingsPath: "/settings?tab=expense-categories",
    fetchSummary: async (categoryId: number): Promise<CategoryMergeSummary> => {
      const summary: ExpenseCategoryMergeSummary =
        await fetchExpenseCategoryMergeSummary(categoryId);
      return {
        sourceCategory: summary.sourceCategory,
        transactionsCount: summary.expensesCount,
        targetCategories: summary.targetCategories,
      };
    },
    merge: mergeExpenseCategory,
  },
  income: {
    pageDescription:
      "Move all income entries from one category into another income category.",
    countLabel: "Income",
    loadError:
      "Income category merge details could not be loaded. Try again in a moment.",
    submitError:
      "Income category could not be merged. Check the selected category and try again.",
    settingsPath: "/settings?tab=income-categories",
    fetchSummary: async (categoryId: number): Promise<CategoryMergeSummary> => {
      const summary: IncomeCategoryMergeSummary =
        await fetchIncomeCategoryMergeSummary(categoryId);
      return {
        sourceCategory: summary.sourceCategory,
        transactionsCount: summary.incomesCount,
        targetCategories: summary.targetCategories,
      };
    },
    merge: mergeIncomeCategory,
  },
} satisfies Record<
  CategoryKind,
  {
    pageDescription: string;
    countLabel: string;
    loadError: string;
    submitError: string;
    settingsPath: string;
    fetchSummary: (categoryId: number) => Promise<CategoryMergeSummary>;
    merge: (categoryId: number, targetCategoryId: number) => Promise<void>;
  }
>;

export function MergeExpenseCategoryPage() {
  return <MergeCategoryPage kind="expense" />;
}

export function MergeIncomeCategoryPage() {
  return <MergeCategoryPage kind="income" />;
}

function MergeCategoryPage({ kind }: { kind: CategoryKind }) {
  const navigate = useNavigate();
  const { categoryId } = useParams();
  const config = categoryConfig[kind];
  const [summary, setSummary] = useState<CategoryMergeSummary>();
  const [targetCategoryId, setTargetCategoryId] = useState<number>();
  const [isTargetDropdownOpen, setIsTargetDropdownOpen] = useState(false);
  const [error, setError] = useState<string>();
  const [targetError, setTargetError] = useState<string>();
  const [isLoading, setIsLoading] = useState(true);
  const [isSubmitting, setIsSubmitting] = useState(false);

  useEffect(() => {
    if (!categoryId) {
      return;
    }

    let isActive = true;
    config
      .fetchSummary(Number(categoryId))
      .then((loadedSummary) => {
        if (!isActive) {
          return;
        }
        setError(undefined);
        setSummary(loadedSummary);
        setTargetCategoryId(loadedSummary.targetCategories[0]?.id);
        setIsLoading(false);
      })
      .catch(() => {
        if (isActive) {
          setError(config.loadError);
          setIsLoading(false);
        }
      });

    return () => {
      isActive = false;
    };
  }, [categoryId, config]);

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!categoryId || !summary) {
      return;
    }
    if (!targetCategoryId) {
      setTargetError("Choose the category to merge into.");
      return;
    }

    setIsSubmitting(true);
    setError(undefined);
    setTargetError(undefined);
    try {
      await config.merge(Number(categoryId), targetCategoryId);
      navigate(config.settingsPath, { replace: true });
    } catch {
      setError(config.submitError);
    } finally {
      setIsSubmitting(false);
    }
  }

  const targetCategories = summary?.targetCategories ?? [];
  const selectedTargetCategory = targetCategories.find(
    (category) => category.id === targetCategoryId,
  );
  const hasTargetCategory = targetCategories.length > 0;
  const visibleError =
    error && (!summary || error !== config.loadError) ? error : undefined;

  return (
    <PageLayout
      title={
        summary ? `Merge ${summary.sourceCategory.name}` : "Merge category"
      }
      description={config.pageDescription}
    >
      <section className="standard-page-panel tracking-account-panel form-loading-container">
        <form className="tracking-account-form" onSubmit={handleSubmit}>
          {visibleError && (
            <Alert
              tone="error"
              title={visibleError}
              className="tracking-account-form-wide"
            />
          )}
          {summary && (
            <>
              <Alert
                tone="warning"
                title="Category merges are irreversible."
                className="tracking-account-form-wide"
              >
                <p>
                  The selected category will receive all linked entries and the
                  current category will be deleted after the merge.
                </p>
              </Alert>
              <div className="tracking-account-form-wide account-merge-summary category-merge-summary">
                <div className="account-merge-stat">
                  <span>{config.countLabel}</span>
                  <strong>{summary.transactionsCount}</strong>
                </div>
              </div>
              {!hasTargetCategory && (
                <Alert
                  tone="error"
                  title="No category available to merge into"
                  className="tracking-account-form-wide"
                >
                  <p>Create another category before merging this category.</p>
                </Alert>
              )}
              <TargetCategoryDropdown
                categories={targetCategories}
                selectedCategory={selectedTargetCategory}
                selectedCategoryId={targetCategoryId}
                isOpen={isTargetDropdownOpen}
                isInvalid={Boolean(targetError)}
                error={targetError}
                onOpenChange={setIsTargetDropdownOpen}
                onChange={(nextCategoryId) => {
                  setTargetCategoryId(nextCategoryId);
                  setTargetError(undefined);
                }}
              />
              <div className="tracking-account-form-wide account-merge-target-summary">
                {selectedTargetCategory ? (
                  <p>
                    <strong>{summary.sourceCategory.name}</strong> will be
                    merged into <strong>{selectedTargetCategory.name}</strong>.
                  </p>
                ) : (
                  <p>No other category is available.</p>
                )}
              </div>
            </>
          )}
          <div className="tracking-account-actions">
            <Button
              color="tertiary"
              size="sm"
              onPress={() => navigate(config.settingsPath)}
              isDisabled={isSubmitting}
            >
              Cancel
            </Button>
            <Button
              color="primary-destructive"
              size="sm"
              type="submit"
              isLoading={isSubmitting}
              isDisabled={!summary || !hasTargetCategory}
            >
              Merge category
            </Button>
          </div>
        </form>
        <FormLoadingOverlay isLoading={isLoading} />
      </section>
    </PageLayout>
  );
}

type TargetCategoryDropdownProps = {
  categories: Category[];
  selectedCategory?: Category;
  selectedCategoryId?: number;
  isOpen: boolean;
  isInvalid: boolean;
  error?: string;
  onOpenChange: (isOpen: boolean) => void;
  onChange: (categoryId: number) => void;
};

function TargetCategoryDropdown({
  categories,
  selectedCategory,
  selectedCategoryId,
  isOpen,
  isInvalid,
  error,
  onOpenChange,
  onChange,
}: TargetCategoryDropdownProps) {
  return (
    <div className="tracking-account-currency-field account-merge-target-field">
      <Label>Merge into category</Label>
      <Dropdown.Root isOpen={isOpen} onOpenChange={onOpenChange}>
        <AriaButton
          aria-label="Merge into category"
          className="tracking-account-currency-trigger"
          isDisabled={categories.length === 0}
        >
          <span className="tracking-account-currency-value">
            <span>{selectedCategory?.name ?? "Choose a category"}</span>
            {categories.length === 0 && <span>No categories available</span>}
          </span>
          <ChevronDown
            aria-hidden="true"
            className="tracking-account-currency-chevron"
          />
        </AriaButton>
        <Dropdown.Popover
          placement="bottom left"
          className="tracking-account-currency-popover"
        >
          <Dropdown.Menu
            selectionMode="single"
            selectedKeys={
              selectedCategoryId ? [String(selectedCategoryId)] : []
            }
            onAction={(key) => {
              onChange(Number(key));
              onOpenChange(false);
            }}
            className="tracking-account-currency-menu"
          >
            {categories.map((category) => (
              <Dropdown.Item
                key={category.id}
                id={String(category.id)}
                textValue={category.name}
              >
                <span className="tracking-account-currency-option">
                  <span>{category.name}</span>
                </span>
              </Dropdown.Item>
            ))}
          </Dropdown.Menu>
        </Dropdown.Popover>
      </Dropdown.Root>
      {isInvalid && <p className="tracking-account-field-error">{error}</p>}
    </div>
  );
}
