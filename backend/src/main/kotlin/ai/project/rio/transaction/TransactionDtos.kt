package ai.project.rio.transaction

import ai.project.rio.http.ValidationException
import ai.project.rio.money.Currency
import ai.project.rio.money.Money
import kotlinx.serialization.Serializable

// HTTP representations. These mirror the files under contracts/schemas exactly.
// Money travels as { "amount": "<integer string>", "currency": "USD" }.

@Serializable
data class MoneyDto(val amount: String, val currency: String)

@Serializable
data class TransactionDto(
    val id: String,
    val description: String,
    val amount: MoneyDto,
    val type: String,
    val status: String,
    val createdAt: String,
)

@Serializable
data class TransactionListResponse(val items: List<TransactionDto>)

@Serializable
data class CreateTransactionRequest(
    val description: String,
    val amount: MoneyDto,
    val type: String,
)

// ---- domain -> wire ----

fun Money.toDto(): MoneyDto = MoneyDto(amount = amount.toString(), currency = currency.code)

fun Transaction.toDto(): TransactionDto = TransactionDto(
    id = id,
    description = description,
    amount = amount.toDto(),
    type = type.name,
    status = status.name,
    createdAt = createdAt.toString(),
)

// ---- wire -> domain (throws ValidationException -> HTTP 400) ----

private val INTEGER_STRING = Regex("^-?(0|[1-9][0-9]*)$")

fun MoneyDto.toMoney(): Money {
    if (!INTEGER_STRING.matches(amount)) {
        throw ValidationException("amount must be a base-10 integer string in minor units")
    }
    val minor = amount.toLongOrNull()
        ?: throw ValidationException("amount is out of range for a 64-bit integer")
    val currency = Currency.fromCode(currency)
        ?: throw ValidationException("unsupported currency; supported: ${Currency.entries.joinToString { it.code }}")
    return Money(minor, currency)
}

fun parseTransactionType(value: String): TransactionType =
    TransactionType.entries.firstOrNull { it.name == value }
        ?: throw ValidationException("type must be one of ${TransactionType.entries.joinToString()}")
