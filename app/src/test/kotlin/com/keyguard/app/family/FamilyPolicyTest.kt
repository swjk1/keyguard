package com.keyguard.app.family

import com.keyguard.app.settings.Intensity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject

/**
 * The floor rule, exhaustively.
 *
 * Every case here is a claim the child-facing supervision screen makes in words, so a
 * regression is not just wrong behaviour — it is the app lying to a child about what their
 * parent can see and change.
 */
class FamilyPolicyTest {

    private fun policy(
        minIntensity: Intensity = Intensity.STANDARD,
        lockSettings: Boolean = false,
        aiVerification: PolicyToggle = PolicyToggle.CHILD_CHOICE,
    ) = FamilyPolicy.DEFAULT.copy(
        minIntensity = minIntensity,
        lockSettings = lockSettings,
        aiVerification = aiVerification,
    )

    @Test
    fun `child may choose a stronger setting than the floor`() {
        val resolved = policy(minIntensity = Intensity.STANDARD)
            .effectiveIntensity(Intensity.INSISTENT)
        assertEquals(Intensity.INSISTENT, resolved)
    }

    @Test
    fun `child cannot choose a weaker setting than the floor`() {
        val resolved = policy(minIntensity = Intensity.STANDARD)
            .effectiveIntensity(Intensity.SUBTLE)
        assertEquals(Intensity.STANDARD, resolved)
    }

    @Test
    fun `an equal choice is left alone`() {
        val resolved = policy(minIntensity = Intensity.STANDARD)
            .effectiveIntensity(Intensity.STANDARD)
        assertEquals(Intensity.STANDARD, resolved)
    }

    @Test
    fun `the lowest floor constrains nothing`() {
        val subtleFloor = policy(minIntensity = Intensity.SUBTLE)
        for (choice in Intensity.entries) {
            assertEquals(choice, subtleFloor.effectiveIntensity(choice), "choice $choice")
        }
    }

    @Test
    fun `locking pins the value in both directions`() {
        val locked = policy(minIntensity = Intensity.STANDARD, lockSettings = true)
        assertEquals(Intensity.STANDARD, locked.effectiveIntensity(Intensity.SUBTLE))
        assertEquals(Intensity.STANDARD, locked.effectiveIntensity(Intensity.INSISTENT))
        assertFalse(locked.childMayChooseIntensity())
    }

    @Test
    fun `an unlocked policy leaves the control usable`() {
        assertTrue(policy().childMayChooseIntensity())
    }

    @Test
    fun `strictness orders the scale as the floor comparison assumes`() {
        val ranked = Intensity.entries.sortedBy(FamilyPolicy::strictness)
        assertEquals(listOf(Intensity.SUBTLE, Intensity.STANDARD, Intensity.INSISTENT), ranked)
    }

    @Test
    fun `child choice defers to the device for AI verification`() {
        val open = policy(aiVerification = PolicyToggle.CHILD_CHOICE)
        assertTrue(open.effectiveAiVerification(childChoice = true))
        assertFalse(open.effectiveAiVerification(childChoice = false))
    }

    @Test
    fun `a forced toggle overrides the device either way`() {
        assertTrue(policy(aiVerification = PolicyToggle.FORCED_ON).effectiveAiVerification(false))
        assertFalse(policy(aiVerification = PolicyToggle.FORCED_OFF).effectiveAiVerification(true))
    }

    @Test
    fun `the default policy changes nothing a child had chosen`() {
        for (choice in Intensity.entries) {
            assertEquals(choice, FamilyPolicy.DEFAULT.effectiveIntensity(choice))
        }
        assertFalse(FamilyPolicy.DEFAULT.effectiveAiVerification(childChoice = false))
        assertTrue(FamilyPolicy.DEFAULT.childMayChooseIntensity())
    }

    @Test
    fun `a policy survives a round trip through JSON`() {
        val original = FamilyPolicy(
            version = 7,
            minIntensity = Intensity.INSISTENT,
            blockAtHigh = false,
            aiVerification = PolicyToggle.FORCED_ON,
            lockSettings = true,
        )
        assertEquals(original, FamilyPolicy.fromJson(JSONObject(original.toJson().toString())))
    }

    @Test
    fun `unreadable fields fall back to the permissive default, never the strictest`() {
        val parsed = FamilyPolicy.fromJson(
            JSONObject("""{"minIntensity":"NONSENSE","aiVerification":"NONSENSE"}"""),
        )
        assertEquals(FamilyPolicy.DEFAULT.minIntensity, parsed.minIntensity)
        assertEquals(Intensity.SUBTLE, parsed.minIntensity, "a parse error must not invent a floor")
        assertEquals(PolicyToggle.CHILD_CHOICE, parsed.aiVerification)
        assertFalse(parsed.lockSettings)
    }
}
