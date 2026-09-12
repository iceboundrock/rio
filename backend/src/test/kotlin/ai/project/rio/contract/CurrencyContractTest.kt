package ai.project.rio.contract

import ai.project.rio.money.Currency
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class CurrencyContractTest {
    @Test
    fun `schema currency enum equals the backend currency set`() {
        val schema = Json.parseToJsonElement(JsonSchemaAssertions.readSchema("money.schema.json")).jsonObject
        val codes = schema.getValue("properties").jsonObject.getValue("currency").jsonObject
            .getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet()

        assertEquals(Currency.entries.map { it.code }.toSet(), codes)
    }
}
