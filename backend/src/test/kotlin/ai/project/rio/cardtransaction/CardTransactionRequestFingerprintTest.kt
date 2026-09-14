package ai.project.rio.cardtransaction

import ai.project.rio.money.Currency
import ai.project.rio.money.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CardTransactionRequestFingerprintTest {

    private val lunch = NewCardTransaction("Lunch", Money(1800, Currency.USD), CardTransactionType.DEBIT)
    private val ramen = NewCardTransaction("Ramen", Money(1200, Currency.JPY), CardTransactionType.CREDIT)

    private fun of(shape: RequestShape, vararg items: NewCardTransaction) = CardTransactionRequestFingerprint.of(shape, items.toList())

    @Test
    fun `known vector`() {
        // printf 'ONE\n5:Lunch|1800|USD|DEBIT' | sha256sum
        assertEquals("ONE\n5:Lunch|1800|USD|DEBIT", CardTransactionRequestFingerprint.canonical(RequestShape.ONE, listOf(lunch)))
        assertEquals("2351721f411f361c9b2a139c4fb5c2cc0d31f669cc5de90e0984c8471a13a233", of(RequestShape.ONE, lunch))
    }

    @Test
    fun `same inputs give the same fingerprint`() {
        assertEquals(of(RequestShape.MANY, lunch, ramen), of(RequestShape.MANY, lunch.copy(), ramen.copy()))
    }

    @Test
    fun `shape is part of the identity`() {
        assertNotEquals(of(RequestShape.ONE, lunch), of(RequestShape.MANY, lunch))
    }

    @Test
    fun `item order is part of the identity`() {
        assertNotEquals(of(RequestShape.MANY, lunch, ramen), of(RequestShape.MANY, ramen, lunch))
    }

    @Test
    fun `every field is part of the identity`() {
        val base = of(RequestShape.ONE, lunch)
        assertNotEquals(base, of(RequestShape.ONE, lunch.copy(description = "Lunch ")))
        assertNotEquals(base, of(RequestShape.ONE, lunch.copy(amount = Money(1801, Currency.USD))))
        assertNotEquals(base, of(RequestShape.ONE, lunch.copy(amount = Money(1800, Currency.EUR))))
        assertNotEquals(base, of(RequestShape.ONE, lunch.copy(type = CardTransactionType.CREDIT)))
    }

    @Test
    fun `length prefix keeps descriptions unambiguous`() {
        assertNotEquals(
            of(RequestShape.ONE, NewCardTransaction("a|1", Money(2, Currency.USD), CardTransactionType.DEBIT)),
            of(RequestShape.ONE, NewCardTransaction("a", Money(1, Currency.USD), CardTransactionType.DEBIT)),
        )
        // A description spelling a whole canonical line must not collide with a real two-item request.
        val forged = NewCardTransaction("Lunch|1800|USD|DEBIT\n5:Ramen", Money(1200, Currency.JPY), CardTransactionType.CREDIT)
        assertNotEquals(of(RequestShape.MANY, lunch, ramen), of(RequestShape.MANY, forged))
    }

    @Test
    fun `non-ASCII descriptions digest deterministically`() {
        val cafe = NewCardTransaction("Café ☕", Money(500, Currency.EUR), CardTransactionType.DEBIT)
        assertEquals("ONE\n6:Café ☕|500|EUR|DEBIT", CardTransactionRequestFingerprint.canonical(RequestShape.ONE, listOf(cafe)))
        assertEquals(of(RequestShape.ONE, cafe), of(RequestShape.ONE, cafe.copy()))
    }

    @Test
    fun `output is 64 lowercase hex characters`() {
        assertTrue(Regex("[0-9a-f]{64}").matches(of(RequestShape.MANY, lunch, ramen)))
    }
}
