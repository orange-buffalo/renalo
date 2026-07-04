To the agents: when requested, address one issue from this file. Once user confirms the issue solve,
remove it from the file.
### Toshl import reconciliation
Update Toshl import to treat "Reconciliation" category records as reconciliation/adjustments records in our model. If expense value in the CSV is provided, adjustments is negative; if income value is provided - positive. Use the actual currency value, not the "In main currency".

---

### Improve logging
When app is started, it logs debug entries, which is annoying and too much. Set the default level to info.
Update all API endoints to log an info entry on start and finish. Ideally should be done with a generic interceptor/filter,
not with custom code each time. Should log current user name, endpoint path, result code.

---
### Password login bruteforce protection
Add a naive bruteforce protection to the password login flow, same as in other similar features - sleep thread for 500-1000 ms.

---
### Configure and issue refresh token when singning in with a passkey
Add a flag to the user account - "issueRefreshTokenOnPasskeyLogin" or similar. Default to true for existing records.

Update user profile page, passkey section. If the flag is up, render an additional description message like "once signed in,
your session will be preserved for longer time". Otherwise "once singed in,your session will be short-lived". Add a text
button left of "create passkey": "prolongate sessions / reduce sessions" based on the flag. The button click updates the settings. Make the wording nice and clean, this are just drafts.

Update the login flow for the passkeys to respect the flag and issue or clean the refresh token accordingly.

---
### Implement PWA

Add PWA support to the app. Extend the profile menu in the topbar with "Install as desktop app" item to request
browser to start installation.

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
### Update money input
Update money input max width to match the input text max width.
On mobile, the max width should be full page width (probably no max width then).

Update money input default value in all forms - must be empty, not 0 as today.

---
### Improve Topbar
1. Expenses, incomes and transfers have the same icons in the topbar. Fix it by having distinct icons for each menu item.
2. Toolbar menu items have broken tooltips (not renderd properly). Remove them completely.
3. Align topbar items centrally on both mobile and desktop. As before, render only icons on mobile, but with labels on desktop.

---
### Inputs width
We added a restriction for the inputs width to be max 300px. But we applied it too widely - to all inputs in the app.
Fix it to only apply to two-column forms, and apply there always. In other places, there should be no default max width 
unless specifically implemented.

On mobile, there should be no max width.

---
### Passkey login button styling
On mobile, passkey login button is full width same as regular login. But on desktop it is narrower.
Fix the passkey login button stylying to the same as regular login button.

---
### Create a logo and favicon
Create a nice logo and favicon based on the app functionality. Provide user with 3 options and iterate until user is happy.
Then integrate into the app.

Add logo to the anonymous pages like login, token activation, etc. Render it above the titel within the panel, centered
horizontally. Make the title on those pages smaller by 20%.

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