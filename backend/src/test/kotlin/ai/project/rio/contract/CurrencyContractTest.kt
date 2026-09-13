package ai.project.rio.contract

import ai.project.rio.money.Currency
import com.alibaba.fastjson2.JSON
import kotlin.test.Test
import kotlin.test.assertEquals

class CurrencyContractTest {
    @Test
    fun `schema currency enum equals the backend currency set`() {
        val schema = JSON.parseObject(JsonSchemaAssertions.readSchema("money.schema.json"))
        val codes = schema.getJSONObject("properties").getJSONObject("currency")
            .getJSONArray("enum").toJavaList(String::class.java).toSet()

        assertEquals(Currency.entries.map { it.code }.toSet(), codes)
    }
}
