package ai.project.rio.cardtransaction

import ai.project.rio.http.ValidationException
import ai.project.rio.money.Currency
import ai.project.rio.money.Money
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject

// HTTP representations. These mirror the files under contracts/schemas exactly.
// Money travels as { "amount": "<integer string>", "currency": "USD" }.

@Serializable
data class MoneyDto(val amount: String, val currency: String)

@Serializable
data class CardTransactionDto(
    val id: String,
    val description: String,
    val amount: MoneyDto,
    val type: String,
    val status: String,
    val createdAt: String,
)

@Serializable
data class CardTransactionListResponse(val items: List<CardTransactionDto>)

@Serializable
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
@Serializable(with = CreateCardTransactionsBodySerializer::class)
sealed class CreateCardTransactionsBody {
    data class One(val request: CreateCardTransactionRequest) : CreateCardTransactionsBody()
    data class Many(val requests: List<CreateCardTransactionRequest>) : CreateCardTransactionsBody()
}

/**
 * Reads the body as a JSON tree to tell an object from an array, then decodes that tree from its
 * text again. The second pass is deliberate: only the streaming decoder annotates a missing field
 * with its JSON path, which ErrorHandling.kt turns into `[1].amount.currency`; decoding the tree
 * directly would report a bare `currency`. The body is therefore parsed twice, and duplicate keys
 * collapse to the last value in the first pass before the second pass sees them.
 */
object CreateCardTransactionsBodySerializer : KSerializer<CreateCardTransactionsBody> {

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("CreateCardTransactionsBody")

    override fun deserialize(decoder: Decoder): CreateCardTransactionsBody {
        val json = decoder as? JsonDecoder ?: throw SerializationException("CreateCardTransactionsBody is JSON only")
        return when (val element = json.decodeJsonElement()) {
            is JsonObject -> CreateCardTransactionsBody.One(
                json.json.decodeFromString(CreateCardTransactionRequest.serializer(), element.toString()),
            )
            is JsonArray -> CreateCardTransactionsBody.Many(
                json.json.decodeFromString(ListSerializer(CreateCardTransactionRequest.serializer()), element.toString()),
            )
            else -> throw SerializationException("request body must be a JSON object or array")
        }
    }

    override fun serialize(encoder: Encoder, value: CreateCardTransactionsBody) =
        throw SerializationException("CreateCardTransactionsBody is a request body and is never serialized")
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
