INSERT INTO expense_categories (user_id, name)
SELECT id, 'General'
FROM users
WHERE type = 'USER'
  AND NOT EXISTS (
      SELECT 1
      FROM expense_categories
      WHERE expense_categories.user_id = users.id
        AND expense_categories.name = 'General'
  );
