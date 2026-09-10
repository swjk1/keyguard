package com.keyguard.app.family

import com.keyguard.app.input.ComposeOutcome
import com.keyguard.detect.Category
import com.keyguard.detect.Finding
import com.keyguard.detect.ScanResult
import com.keyguard.detect.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONObject

class SupervisionEventTest {

    private fun event(
        category: Category = Category.PII_DISCLOSURE,
        severity: Severity = Severity.HIGH,
    ) = SupervisionEvent(
        at = 1_700_000_000_000,
        category = category,
        severity = severity,
        outcome = ComposeOutcome.ABANDONED_DELETED,
        heeded = true,
    )

    private fun finding(category: Category, severity: Severity, start: Int) = Finding(
        start = start,
        end = start + 4,
        category = category,
        severity = severity,
        ruleId = "test.$start",
        message = "",
    )

    @Test
    fun `an event survives a round trip`() {
        val original = event()
        val restored = SupervisionEvent.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(original, restored)
    }

    @Test
    fun `the serialized form carries no text field of any kind`() {
        // The whole privacy argument for parent reporting rests on this. Asserted against the
        // wire bytes rather than the data class, since that is what actually leaves the phone.
        val json = event().toJson().toString()
        for (key in JSONObject(json).keys()) {
            val value = JSONObject(json).get(key)
            assertTrue(
                value !is String || key == "category" || key == "outcome",
                "unexpected free-text field '$key' in the supervision payload",
            )
        }
    }

    @Test
    fun `an unknown category is dropped rather than defaulted`() {
        val forged = JSONObject(event().toJson().toString()).put("category", "FUTURE_CATEGORY")
        assertNull(SupervisionEvent.fromJson(forged))
    }

    @Test
    fun `a missing field is dropped`() {
        val truncated = JSONObject(event().toJson().toString()).apply { remove("severity") }
        assertNull(SupervisionEvent.fromJson(truncated))
    }

    @Test
    fun `the reported category is the one carrying the highest severity`() {
        val result = ScanResult.EMPTY.copy(
            findings = listOf(
                finding(Category.SUBSTANCE, Severity.LOW, start = 0),
                finding(Category.SELF_HARM, Severity.HIGH, start = 10),
                finding(Category.HARASSMENT, Severity.MEDIUM, start = 20),
            ),
            maxSeverity = Severity.HIGH,
        )
        assertEquals(Category.SELF_HARM, SupervisionEvent.dominantCategory(result))
    }

    @Test
    fun `ties resolve to the first finding, so the same buffer always reports the same way`() {
        val result = ScanResult.EMPTY.copy(
            findings = listOf(
                finding(Category.HARASSMENT, Severity.MEDIUM, start = 0),
                finding(Category.SUBSTANCE, Severity.MEDIUM, start = 10),
            ),
            maxSeverity = Severity.MEDIUM,
        )
        assertEquals(Category.HARASSMENT, SupervisionEvent.dominantCategory(result))
    }

    @Test
    fun `a scan with nothing in it produces no category`() {
        assertNull(SupervisionEvent.dominantCategory(ScanResult.EMPTY))
    }
}
