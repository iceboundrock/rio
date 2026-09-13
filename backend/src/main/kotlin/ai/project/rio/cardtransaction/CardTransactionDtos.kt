package ai.project.rio.cardtransaction

import ai.project.rio.http.ValidationException
import ai.project.rio.money.Currency
import ai.project.rio.money.Money
import com.alibaba.fastjson2.JSONException
import com.alibaba.fastjson2.JSONReader
import com.alibaba.fastjson2.annotation.JSONType
import com.alibaba.fastjson2.reader.ObjectReader
import java.lang.reflect.Type

// HTTP representations. These mirror the files under contracts/schemas exactly.
// Money travels as { "amount": "<integer string>", "currency": "USD" }.

data class MoneyDto(val amount: String, val currency: String)

data class CardTransactionDto(
    val id: String,
    val description: String,
    val amount: MoneyDto,
    val type: String,
    val status: String,
    val createdAt: String,
)

data class CardTransactionListResponse(val items: List<CardTransactionDto>)

data class CreateCardTransactionRequest(
    val description: String,
    val amount: MoneyDto,
    val type: String,
)

/**
 * Body of POST /api/card-transactions (create-card-transactions-request.schema.json): one request
 * object, answered with one CardTransaction, or a non-empty array of them, created all-or-nothing and
 * answered with the list shape.
 */
@JSONType(deserializer = CreateCardTransactionsBodyReader::class)
sealed class CreateCardTransactionsBody {
    data class One(val request: CreateCardTransactionRequest) : CreateCardTransactionsBody()
    data class Many(val requests: List<CreateCardTransactionRequest>) : CreateCardTransactionsBody()
}

/**
 * Dispatches on the first token - `{` is one request, `[` is a list of them - and reads the body once.
 * The nested reads share this reader's context, so unknown keys are still rejected inside the items.
 * Anything else (a scalar, `null`, a null array element) is not a create request at all and fails
 * here, which ErrorHandling.kt answers as 400 `malformed request body`.
 */
class CreateCardTransactionsBodyReader : ObjectReader<CreateCardTransactionsBody> {

    override fun readObject(jsonReader: JSONReader, fieldType: Type?, fieldName: Any?, features: Long): CreateCardTransactionsBody =
        when {
            jsonReader.isObject -> CreateCardTransactionsBody.One(jsonReader.read(CreateCardTransactionRequest::class.java))
            jsonReader.isArray -> CreateCardTransactionsBody.Many(
                // readArray yields a null element for `[null]`; a null request would only surface later
                // as a NullPointerException inside the route, i.e. a 500 for a client mistake.
                jsonReader.readArray(CreateCardTransactionRequest::class.java).map {
                    it as? CreateCardTransactionRequest ?: throw JSONException("array items must be JSON objects")
                },
            )
            else -> throw JSONException("request body must be a JSON object or array")
        }
}

// ---- domain -> wire ----

fun Money.toDto(): MoneyDto = MoneyDto(amount = amount.toString(), currency = currency.code)

fun CardTransaction.toDto(): CardTransactionDto = CardTransactionDto(
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

fun parseCardTransactionType(value: String): CardTransactionType =
    CardTransactionType.entries.firstOrNull { it.name == value }
        ?: throw ValidationException("type must be one of ${CardTransactionType.entries.joinToString()}")

fun CreateCardTransactionRequest.toNewCardTransaction(): NewCardTransaction = NewCardTransaction(
    description = description,
    amount = amount.toMoney(),
    type = parseCardTransactionType(type),
)
