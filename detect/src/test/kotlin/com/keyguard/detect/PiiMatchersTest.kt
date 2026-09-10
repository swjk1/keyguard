package com.keyguard.detect

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PiiMatchersTest {

    private fun rules(text: String): Set<String> =
        PiiMatchers.match(text).map { it.ruleId }.toSet()

    @Test
    fun `luhn rejects digit runs that are not card numbers`() {
        // Without the checksum, the highest-severity rule in the pack would fire on every
        // long number a teenager types — order ids, game ids, dates.
        assertTrue(PiiMatchers.luhnValid("4111111111111111"), "known-valid Visa test number")
        assertTrue(PiiMatchers.luhnValid("5500 0000 0000 0004"), "known-valid Mastercard test number")
        assertFalse(PiiMatchers.luhnValid("1234567890123456"), "arbitrary digits")
        assertFalse(PiiMatchers.luhnValid("4111111111111112"), "corrupted check digit")
        assertFalse(PiiMatchers.luhnValid("411111111111"), "too short to be a card")
    }

    @Test
    fun `detects a card number only when the checksum holds`() {
        assertTrue("pii.credit_card" in rules("my card is 4111 1111 1111 1111"))
        assertFalse("pii.credit_card" in rules("my order id is 1234 5678 9012 3456"))
    }

    @Test
    fun `detects contact details`() {
        assertTrue("pii.email" in rules("email me at a.b-c@sub.example.co.uk"))
        assertTrue("pii.phone.nanp" in rules("ring 555-123-4567"))
        assertTrue("pii.phone.intl" in rules("im on +44 7700 900123"))
    }

    @Test
    fun `detects street addresses`() {
        assertTrue("pii.street_address" in rules("i'm at 4472 Oakwood Drive"))
        assertTrue("pii.street_address" in rules("42 elm st"))
        assertFalse("pii.street_address" in rules("i scored 42 points"))
    }

    @Test
    fun `age disclosure fires only for plausible minor ages`() {
        assertTrue("pii.self_disclose.age_minor" in rules("im 14 btw"))
        assertTrue("pii.self_disclose.age_minor" in rules("i'm 9 years old"))
        assertFalse("pii.self_disclose.age_minor" in rules("im 34 btw"))
        assertFalse("pii.self_disclose.age_minor" in rules("im 99 problems"))
    }

    @Test
    fun `name disclosure requires an actual introduction`() {
        assertTrue("pii.self_disclose.name" in rules("my name is Sam"))
        assertTrue("pii.self_disclose.name" in rules("you can call me Alex"))
        assertFalse("pii.self_disclose.name" in rules("call me when you land"))
    }

    @Test
    fun `underlines only the disclosed name not the whole phrase`() {
        val finding = PiiMatchers.match("my name is Priya")
            .single { it.ruleId == "pii.self_disclose.name" }
        assertEquals("Priya", "my name is Priya".substring(finding.start, finding.end))
    }

    @Test
    fun `ssn pattern rejects structurally invalid numbers`() {
        assertTrue("pii.ssn" in rules("ssn 123-45-6789"))
        assertFalse("pii.ssn" in rules("ssn 000-45-6789"), "area 000 is never issued")
        assertFalse("pii.ssn" in rules("ssn 123-00-6789"), "group 00 is never issued")
        assertFalse("pii.ssn" in rules("ssn 123-45-0000"), "serial 0000 is never issued")
    }

    @Test
    fun `does not fire on ordinary sentences`() {
        assertTrue(rules("hey are we still on for tomorrow").isEmpty())
        assertTrue(rules("i scored 3 goals today").isEmpty())
    }
}
