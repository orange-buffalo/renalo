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

        var importedExpenses = 0
        var importedIncomes = 0
        var skippedDuplicateExpenses = 0
        var skippedDuplicateIncomes = 0

        parsedRows
            .filter { it !in reconciledTransferRows }
            .filterNot { it.category.equals("Transfer", ignoreCase = true) }
            .forEach { row ->
                when (row.type) {
                    TransactionType.EXPENSE -> {
                        if (importTransaction(userId, row)) {
                            importedExpenses++
                        } else {
                            skippedDuplicateExpenses++
                        }
                    }
                    TransactionType.INCOME -> {
                        if (importTransaction(userId, row)) {
                            importedIncomes++
                        } else {
                            skippedDuplicateIncomes++
                        }
                    }
                }
            }

        val transferImportResult = importTransfers(userId, reconciledTransfers)
        val unreconciledTransfers = transferRows.filter { it !in reconciledTransferRows }

        return ToshlImportResult(
            importedExpenses = importedExpenses,
            importedIncomes = importedIncomes,
            skippedDuplicateExpenses = skippedDuplicateExpenses,
            skippedDuplicateIncomes = skippedDuplicateIncomes,
            importedTransfers = transferImportResult.imported,
            skippedDuplicateTransfers = transferImportResult.skippedDuplicates,
            warnings = unreconciledTransfers.map { it.toWarning() },
        )
    }

    private fun importTransaction(userId: Long, row: ToshlRow): Boolean {
        if (transactionRepository.findByUserIdAndTypeAndDateAndAmountMinor(
                userId,
                row.type,
                row.date,
                row.amountMinor,
            ).isNotEmpty()
        ) {
            return false
        }

        val account = findOrCreateAccount(userId, row.account, row.currency)
        val categoryId = when (row.type) {
            TransactionType.EXPENSE -> findOrCreateExpenseCategory(userId, row.category).id!!
            TransactionType.INCOME -> findOrCreateIncomeCategory(userId, row.category).id!!
        }
        transactionRepository.save(
            Transaction(
                userId = userId,
                type = row.type,
                trackingAccountId = account.id!!,
                categoryId = categoryId,
                date = row.date,
                amountMinor = row.amountMinor,
                notes = row.notes,
                metadata = mapOf("source" to "toshl"),
            ),
        )
        return true
    }

    private fun importTransfers(userId: Long, transfers: List<ReconciledToshlTransfer>): TransferImportResult {
        var imported = 0
        var skippedDuplicates = 0
        transfers.forEach { transfer ->
            val sourceAccount = findOrCreateAccount(userId, transfer.source.account, transfer.source.currency)
            val targetAccount = findOrCreateAccount(userId, transfer.target.account, transfer.target.currency)
            val sourceAmountMinor = transfer.source.amountForAccount(sourceAccount)
            val targetAmountMinor = transfer.target.amountForAccount(targetAccount)
            val existing = fundsTransferRepository
                .findByUserIdAndDateAndSourceAccountIdAndTargetAccountIdAndSourceAmountMinorAndTargetAmountMinor(
                    userId = userId,
                    date = transfer.source.date,
                    sourceAccountId = sourceAccount.id!!,
                    targetAccountId = targetAccount.id!!,
                    sourceAmountMinor = sourceAmountMinor,
                    targetAmountMinor = targetAmountMinor,
                )
            if (existing.isNotEmpty()) {
                skippedDuplicates++
            } else {
                fundsTransferRepository.save(
                    FundsTransfer(
                        userId = userId,
                        sourceAccountId = sourceAccount.id!!,
                        targetAccountId = targetAccount.id!!,
                        sourceAmountMinor = sourceAmountMinor,
                        targetAmountMinor = targetAmountMinor,
                        date = transfer.source.date,
                    ),
                )
                imported++
            }
        }
        return TransferImportResult(imported, skippedDuplicates)
    }

    private fun reconcileTransfers(transferRows: List<ToshlRow>): List<ReconciledToshlTransfer> {
        val expenseTransfers = transferRows.filter { it.type == TransactionType.EXPENSE }.toMutableList()
        val incomeTransfers = transferRows.filter { it.type == TransactionType.INCOME }.toMutableList()
        val reconciled = mutableListOf<ReconciledToshlTransfer>()

        val expenseIterator = expenseTransfers.iterator()
        while (expenseIterator.hasNext()) {
            val expense = expenseIterator.next()
            val income = incomeTransfers.firstOrNull {
                it.date == expense.date && it.mainCurrency == expense.mainCurrency && it.mainAmountMinor == expense.mainAmountMinor
            } ?: continue
            if (expense.account == income.account) {
                continue
            }
            reconciled += ReconciledToshlTransfer(source = expense, target = income)
            expenseIterator.remove()
            incomeTransfers.remove(income)
        }

        return reconciled
    }

    private fun findOrCreateAccount(userId: Long, name: String, currency: String): TrackingAccount {
        trackingAccountRepository.findByUserIdOrderByName(userId)
            .firstOrNull { it.name == name }
            ?.let { return it }
        val shouldBeDefault = trackingAccountRepository.countByUserId(userId) == 0L
        return trackingAccountRepository.save(
            TrackingAccount(
                userId = userId,
                name = name,
                currency = currency,
                initialBalanceMinor = 0,
                isDefault = shouldBeDefault,
            ),
        )
    }

    private fun findOrCreateExpenseCategory(userId: Long, name: String): ExpenseCategory {
        expenseCategoryRepository.findByUserIdOrderByName(userId)
            .firstOrNull { it.name == name }
            ?.let { return it }
        return expenseCategoryRepository.save(ExpenseCategory(userId = userId, name = name))
    }

    private fun findOrCreateIncomeCategory(userId: Long, name: String): IncomeCategory {
        incomeCategoryRepository.findByUserIdOrderByName(userId)
            .firstOrNull { it.name == name }
            ?.let { return it }
        return incomeCategoryRepository.save(IncomeCategory(userId = userId, name = name))
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

private data class ReconciledToshlTransfer(
    val source: ToshlRow,
    val target: ToshlRow,
)

private data class TransferImportResult(
    val imported: Int,
    val skippedDuplicates: Int,
)
