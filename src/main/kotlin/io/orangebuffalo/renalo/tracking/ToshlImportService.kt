package io.orangebuffalo.renalo.tracking

import com.fasterxml.jackson.annotation.JsonInclude
import io.micronaut.transaction.annotation.Transactional
import jakarta.inject.Singleton
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency

@Singleton
open class ToshlImportService(
    private val trackingAccountRepository: TrackingAccountRepository,
    private val expenseCategoryRepository: ExpenseCategoryRepository,
    private val incomeCategoryRepository: IncomeCategoryRepository,
    private val transactionRepository: TransactionRepository,
    private val fundsTransferRepository: FundsTransferRepository,
) {
    @Transactional
    open fun import(userId: Long, csvContent: String): ToshlImportResult {
        val rows = parseCsv(csvContent)
        if (rows.isEmpty()) {
            throw ToshlImportException("CSV file is empty")
        }
        val header = rows.first().mapIndexed { index, name -> cleanHeader(name) to index }.toMap()
        val dataRows = rows.drop(1).filter { row -> row.any { it.isNotBlank() } }
        val parsedRows = dataRows.mapIndexed { index, row -> parseRow(header, row, index + 2) }
        val transferRows = parsedRows.filter { it.category.equals("Transfer", ignoreCase = true) }
        val reconciledTransfers = reconcileTransfers(transferRows)
        val reconciledTransferRows = reconciledTransfers.flatMap { listOf(it.source, it.target) }.toSet()
        val transactionRows = parsedRows
            .filter { it !in reconciledTransferRows }
            .filterNot { it.category.equals("Transfer", ignoreCase = true) }
        val context = loadImportContext(userId)
        val report = mutableListOf<ToshlImportReportEntry>()

        createMissingReferences(userId, context, transactionRows, reconciledTransfers)
        importTransactions(userId, context, transactionRows, report)
        val transferImportResult = importTransfers(userId, context, reconciledTransfers, report)

        val unreconciledTransfers = transferRows.filter { it !in reconciledTransferRows }
        unreconciledTransfers.forEach { row ->
            report += row.toReportEntry("UNMATCHED_TRANSFER", "Transfer row could not be matched with its opposite side.")
        }

        return ToshlImportResult(
            importedExpenses = report.count { it.status == "IMPORTED" && it.reason == "Imported as expense." },
            importedIncomes = report.count { it.status == "IMPORTED" && it.reason == "Imported as income." },
            skippedDuplicateExpenses = report.count { it.status == "SKIPPED_DUPLICATE" && it.type == TransactionType.EXPENSE && it.reason.startsWith("Duplicate expense") },
            skippedDuplicateIncomes = report.count { it.status == "SKIPPED_DUPLICATE" && it.type == TransactionType.INCOME && it.reason.startsWith("Duplicate income") },
            importedTransfers = transferImportResult.imported,
            skippedDuplicateTransfers = transferImportResult.skippedDuplicates,
            warnings = unreconciledTransfers.map { it.toWarning() },
            report = report.sortedBy { it.lineNumber },
        )
    }

    private fun loadImportContext(userId: Long): ToshlImportContext {
        val transactionKeys = TransactionType.entries
            .flatMap { type -> transactionRepository.findByUserIdAndTypeOrderByDateDesc(userId, type) }
            .mapTo(mutableSetOf()) { TransactionImportKey(it.type, it.date, it.amountMinor) }
        val transferKeys = fundsTransferRepository.findByUserIdOrderByDateDesc(userId)
            .mapTo(mutableSetOf()) {
                TransferImportKey(
                    date = it.date,
                    sourceAccountId = it.sourceAccountId,
                    targetAccountId = it.targetAccountId,
                    sourceAmountMinor = it.sourceAmountMinor,
                    targetAmountMinor = it.targetAmountMinor,
                )
            }
        return ToshlImportContext(
            accountsByName = trackingAccountRepository.findByUserIdOrderByName(userId).associateByTo(mutableMapOf()) { it.name },
            expenseCategoriesByName = expenseCategoryRepository.findByUserIdOrderByName(userId).associateByTo(mutableMapOf()) { it.name },
            incomeCategoriesByName = incomeCategoryRepository.findByUserIdOrderByName(userId).associateByTo(mutableMapOf()) { it.name },
            transactionKeys = transactionKeys,
            transferKeys = transferKeys,
        )
    }

    private fun createMissingReferences(
        userId: Long,
        context: ToshlImportContext,
        transactionRows: List<ToshlRow>,
        transfers: List<ReconciledToshlTransfer>,
    ) {
        val transferRows = transfers.flatMap { listOf(it.source, it.target) }
        val allRowsWithAccounts = transactionRows + transferRows
        val missingAccounts = allRowsWithAccounts
            .map { it.account }
            .distinct()
            .filterNot { it in context.accountsByName }
        if (missingAccounts.isNotEmpty()) {
            val hasExistingAccounts = context.accountsByName.isNotEmpty()
            val accountCurrencyByName = allRowsWithAccounts.associate { it.account to it.currency }
            val accountsToSave = missingAccounts.mapIndexed { index, accountName ->
                TrackingAccount(
                    userId = userId,
                    name = accountName,
                    currency = accountCurrencyByName.getValue(accountName),
                    initialBalanceMinor = 0,
                    isDefault = !hasExistingAccounts && index == 0,
                )
            }
            trackingAccountRepository.saveAll(accountsToSave).forEach { context.accountsByName[it.name] = it }
        }

        val missingExpenseCategories = transactionRows
            .filter { it.type == TransactionType.EXPENSE }
            .map { it.category }
            .distinct()
            .filterNot { it in context.expenseCategoriesByName }
        if (missingExpenseCategories.isNotEmpty()) {
            expenseCategoryRepository.saveAll(missingExpenseCategories.map { ExpenseCategory(userId = userId, name = it) })
                .forEach { context.expenseCategoriesByName[it.name] = it }
        }

        val missingIncomeCategories = transactionRows
            .filter { it.type == TransactionType.INCOME }
            .map { it.category }
            .distinct()
            .filterNot { it in context.incomeCategoriesByName }
        if (missingIncomeCategories.isNotEmpty()) {
            incomeCategoryRepository.saveAll(missingIncomeCategories.map { IncomeCategory(userId = userId, name = it) })
                .forEach { context.incomeCategoriesByName[it.name] = it }
        }
    }

    private fun importTransactions(
        userId: Long,
        context: ToshlImportContext,
        rows: List<ToshlRow>,
        report: MutableList<ToshlImportReportEntry>,
    ) {
        val transactionsToImport = rows.mapNotNull { row ->
            val key = TransactionImportKey(row.type, row.date, row.amountMinor)
            if (!context.transactionKeys.add(key)) {
                report += row.toReportEntry(
                    "SKIPPED_DUPLICATE",
                    "Duplicate ${row.type.name.lowercase()} by date, type, and amount.",
                )
                null
            } else {
                report += row.toReportEntry("IMPORTED", "Imported as ${row.type.name.lowercase()}.")
                Transaction(
                    userId = userId,
                    type = row.type,
                    trackingAccountId = context.accountsByName.getValue(row.account).id!!,
                    categoryId = when (row.type) {
                        TransactionType.EXPENSE -> context.expenseCategoriesByName.getValue(row.category).id!!
                        TransactionType.INCOME -> context.incomeCategoriesByName.getValue(row.category).id!!
                    },
                    date = row.date,
                    amountMinor = row.amountMinor,
                    notes = row.notes,
                    metadata = mapOf("source" to "toshl"),
                )
            }
        }
        if (transactionsToImport.isNotEmpty()) {
            transactionRepository.saveAll(transactionsToImport).toList()
        }
    }

    private fun importTransfers(
        userId: Long,
        context: ToshlImportContext,
        transfers: List<ReconciledToshlTransfer>,
        report: MutableList<ToshlImportReportEntry>,
    ): TransferImportResult {
        var imported = 0
        var skippedDuplicates = 0
        val transfersToImport = mutableListOf<FundsTransfer>()
        transfers.forEach { transfer ->
            val sourceAccount = context.accountsByName.getValue(transfer.source.account)
            val targetAccount = context.accountsByName.getValue(transfer.target.account)
            val sourceAmountMinor = transfer.source.amountForAccount(sourceAccount)
            val targetAmountMinor = transfer.target.amountForAccount(targetAccount)
            val key = TransferImportKey(
                date = transfer.source.date,
                sourceAccountId = sourceAccount.id!!,
                targetAccountId = targetAccount.id!!,
                sourceAmountMinor = sourceAmountMinor,
                targetAmountMinor = targetAmountMinor,
            )
            if (!context.transferKeys.add(key)) {
                skippedDuplicates++
                report += transfer.source.toReportEntry("SKIPPED_DUPLICATE", "Duplicate transfer pair.")
                report += transfer.target.toReportEntry("SKIPPED_DUPLICATE", "Duplicate transfer pair.")
            } else {
                transfersToImport += FundsTransfer(
                    userId = userId,
                    sourceAccountId = sourceAccount.id!!,
                    targetAccountId = targetAccount.id!!,
                    sourceAmountMinor = sourceAmountMinor,
                    targetAmountMinor = targetAmountMinor,
                    date = transfer.source.date,
                )
                report += transfer.source.toReportEntry("IMPORTED", "Imported as transfer source.")
                report += transfer.target.toReportEntry("IMPORTED", "Imported as transfer target.")
                imported++
            }
        }
        if (transfersToImport.isNotEmpty()) {
            fundsTransferRepository.saveAll(transfersToImport).toList()
        }
        return TransferImportResult(imported, skippedDuplicates)
    }

    private fun reconcileTransfers(transferRows: List<ToshlRow>): List<ReconciledToshlTransfer> {
        val reconciled = mutableListOf<ReconciledToshlTransfer>()
        val transferGroups = transferRows.groupBy { TransferMatchKey(it.date, it.mainCurrency, it.mainAmountMinor) }

        transferGroups.values.forEach { groupRows ->
            val incomeTransfers = groupRows.filter { it.type == TransactionType.INCOME }.toMutableList()
            groupRows.filter { it.type == TransactionType.EXPENSE }.forEach { expense ->
                val income = incomeTransfers.firstOrNull { it.account != expense.account } ?: return@forEach
                reconciled += ReconciledToshlTransfer(source = expense, target = income)
                incomeTransfers.remove(income)
            }
        }

        return reconciled
    }

    private fun parseRow(header: Map<String, Int>, row: List<String>, lineNumber: Int): ToshlRow {
        val expenseAmount = value(row, header, "Expense amount", lineNumber).parseAmountOrZero()
        val incomeAmount = value(row, header, "Income amount", lineNumber).parseAmountOrZero()
        val type = when {
            expenseAmount > BigDecimal.ZERO && incomeAmount == BigDecimal.ZERO -> TransactionType.EXPENSE
            incomeAmount > BigDecimal.ZERO && expenseAmount == BigDecimal.ZERO -> TransactionType.INCOME
            else -> throw ToshlImportException("Line $lineNumber must contain either an expense amount or an income amount")
        }
        val amount = if (type == TransactionType.EXPENSE) expenseAmount else incomeAmount
        val currency = value(row, header, "Currency", lineNumber).trim().uppercase()
        val mainCurrency = value(row, header, "Main currency", lineNumber).trim().uppercase()
        val description = value(row, header, "Description", lineNumber).trim()
        val tags = value(row, header, "Tags", lineNumber).trim()

        return ToshlRow(
            lineNumber = lineNumber,
            date = LocalDate.parse(value(row, header, "Date", lineNumber).trim(), toshlDateFormatter),
            account = value(row, header, "Account", lineNumber).trim().takeIf { it.isNotBlank() }
                ?: throw ToshlImportException("Line $lineNumber is missing account"),
            category = value(row, header, "Category", lineNumber).trim().takeIf { it.isNotBlank() }
                ?: throw ToshlImportException("Line $lineNumber is missing category"),
            type = type,
            amountMinor = amount.toMinorUnits(currency, lineNumber),
            currency = currency,
            mainAmountMinor = value(row, header, "In main currency", lineNumber).parseAmountOrZero().toMinorUnits(mainCurrency, lineNumber),
            mainCurrency = mainCurrency,
            notes = buildNotes(description, tags),
        )
    }

    private fun value(row: List<String>, header: Map<String, Int>, column: String, lineNumber: Int): String {
        val index = header[column] ?: throw ToshlImportException("CSV file is missing '$column' column")
        return row.getOrNull(index) ?: throw ToshlImportException("Line $lineNumber is missing '$column' value")
    }

    private fun buildNotes(description: String, tags: String): String? {
        val descriptionPart = description.takeIf { it.isNotBlank() }
        val tagsPart = tags.takeIf { it.isNotBlank() }?.let { "Tags: $it" }
        return listOfNotNull(descriptionPart, tagsPart).joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun ToshlRow.toWarning() = ToshlImportWarning(
        lineNumber = lineNumber,
        date = date,
        account = account,
        amountMinor = amountMinor,
        currency = currency,
        type = type,
        description = "Transfer row could not be matched with its opposite side.",
    )

    private fun ToshlRow.toReportEntry(status: String, reason: String) = ToshlImportReportEntry(
        lineNumber = lineNumber,
        date = date,
        account = account,
        category = category,
        type = type,
        amountMinor = amountMinor,
        currency = currency,
        status = status,
        reason = reason,
    )

    private fun ToshlRow.amountForAccount(account: TrackingAccount): Long = when (account.currency) {
        currency -> amountMinor
        mainCurrency -> mainAmountMinor
        else -> amountMinor
    }

    private fun String.parseAmountOrZero(): BigDecimal = trim()
        .replace(",", "")
        .takeIf { it.isNotBlank() }
        ?.let { BigDecimal(it) }
        ?: BigDecimal.ZERO

    private fun BigDecimal.toMinorUnits(currencyCode: String, lineNumber: Int): Long {
        val fractionDigits = try {
            Currency.getInstance(currencyCode).defaultFractionDigits
        } catch (_: IllegalArgumentException) {
            throw ToshlImportException("Line $lineNumber uses unsupported currency '$currencyCode'")
        }
        return movePointRight(fractionDigits)
            .setScale(0, RoundingMode.UNNECESSARY)
            .longValueExact()
    }

    private fun parseCsv(content: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentRow = mutableListOf<String>()
        val currentValue = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < content.length) {
            val char = content[index]
            when {
                char == '"' && inQuotes && content.getOrNull(index + 1) == '"' -> {
                    currentValue.append('"')
                    index++
                }
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    currentRow += currentValue.toString()
                    currentValue.clear()
                }
                (char == '\n' || char == '\r') && !inQuotes -> {
                    if (char == '\r' && content.getOrNull(index + 1) == '\n') {
                        index++
                    }
                    currentRow += currentValue.toString()
                    currentValue.clear()
                    rows += currentRow.toList()
                    currentRow.clear()
                }
                else -> currentValue.append(char)
            }
            index++
        }
        if (currentValue.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow += currentValue.toString()
            rows += currentRow.toList()
        }
        return rows
    }

    private fun cleanHeader(name: String) = name.removePrefix("\uFEFF").trim()

    companion object {
        private val toshlDateFormatter = DateTimeFormatter.ofPattern("d/M/yy")
    }
}

class ToshlImportException(message: String) : RuntimeException(message)

data class ToshlImportResult(
    val importedExpenses: Int,
    val importedIncomes: Int,
    val skippedDuplicateExpenses: Int,
    val skippedDuplicateIncomes: Int,
    val importedTransfers: Int,
    val skippedDuplicateTransfers: Int,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val warnings: List<ToshlImportWarning>,
    @field:JsonInclude(JsonInclude.Include.ALWAYS)
    val report: List<ToshlImportReportEntry>,
)

data class ToshlImportWarning(
    val lineNumber: Int,
    val date: LocalDate,
    val account: String,
    val amountMinor: Long,
    val currency: String,
    val type: TransactionType,
    val description: String,
)

data class ToshlImportReportEntry(
    val lineNumber: Int,
    val date: LocalDate,
    val account: String,
    val category: String,
    val type: TransactionType,
    val amountMinor: Long,
    val currency: String,
    val status: String,
    val reason: String,
)

data class ToshlImportRequest(
    val csvContent: String,
)

private data class ToshlRow(
    val lineNumber: Int,
    val date: LocalDate,
    val account: String,
    val category: String,
    val type: TransactionType,
    val amountMinor: Long,
    val currency: String,
    val mainAmountMinor: Long,
    val mainCurrency: String,
    val notes: String?,
)

private data class ToshlImportContext(
    val accountsByName: MutableMap<String, TrackingAccount>,
    val expenseCategoriesByName: MutableMap<String, ExpenseCategory>,
    val incomeCategoriesByName: MutableMap<String, IncomeCategory>,
    val transactionKeys: MutableSet<TransactionImportKey>,
    val transferKeys: MutableSet<TransferImportKey>,
)

private data class TransactionImportKey(
    val type: TransactionType,
    val date: LocalDate,
    val amountMinor: Long,
)

private data class TransferImportKey(
    val date: LocalDate,
    val sourceAccountId: Long,
    val targetAccountId: Long,
    val sourceAmountMinor: Long,
    val targetAmountMinor: Long,
)

private data class TransferMatchKey(
    val date: LocalDate,
    val mainCurrency: String,
    val mainAmountMinor: Long,
)

private data class ReconciledToshlTransfer(
    val source: ToshlRow,
    val target: ToshlRow,
)

private data class TransferImportResult(
    val imported: Int,
    val skippedDuplicates: Int,
)
