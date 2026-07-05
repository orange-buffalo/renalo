To the agents: when requested, address one issue from this file. Once user confirms the issue solve,
remove it from the file.

---

### Persist accounts selection
On all forms where transactional accounts are selected, persist the input selection in local storage,
and restore the last selected value on form load. This also includes pages with multiple accounts -
each to be saved independently.

If persisted account is not available (either no value stored or acocunt no longer exists), use the default account.

---
### Update categories selection
On all forms where categories are selected in dropdowns (create/edit forms, filters, etc.),
show the categories in the order of usage. Derive the usage from the linked transactions.
In case of the same last used date, order alphabetically. 

On create forms, do not select the first category by default. The selection must be empty.

---
### Update money input default value
Update money input default value in all forms - must be empty, not 0 as today.

---
### Preserve the "remember me" selection
Preserve the selection of "remeber me" and value of "username" on the singin page between reloads. Store in the
local store.

---
### Dashboard loading state
When dashboard data is being loaded, ugly text message is displayed. Replace with loading indicator we use for forms. Render it centered horizontally; with some margin on top and bottom (e.g. 50px).

---
### Reduce page header spacing
On desktop, the page header has a huge spacing after the topbar. Reduce it by roughly 70%.