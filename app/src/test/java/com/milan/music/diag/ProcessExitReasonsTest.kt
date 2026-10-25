package com.milan.music.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 原因码取自 `ApplicationExitInfo`：LOW_MEMORY=3、CRASH=4、CRASH_NATIVE=5、ANR=6、
 * OTHER=13。分类器不直接引用这些常量，因为它们在 API 30 才出现、而 minSdk 是 26。
 */
class ProcessExitReasonsTest {

    private val memoryLimiter = "MemoryLimiter:AnonSwap"

    @Test
    fun `memory limiter kill needs both REASON_OTHER and the description fingerprint`() {
        val exit = sampleExit(reason = 13, description = memoryLimiter)
        assertEquals(ExitCause.MEMORY_LIMITER, exit.cause)
        assertEquals(
            "exit cause=memory-limiter reason=13 atMs=1700000000000 " +
                "rss=120MB pss=98MB desc=MemoryLimiter:AnonSwap",
            exit.describe(),
        )
    }

    @Test
    fun `REASON_OTHER alone is not a memory limiter kill`() {
        assertEquals(ExitCause.OTHER, classifyProcessExit(13, null))
        assertEquals(ExitCause.OTHER, classifyProcessExit(13, "package update"))
        assertFalse(isReportable(classifyProcessExit(13, "package update")))
    }

    @Test
    fun `low memory lmk kills keep their own category`() {
        assertEquals(ExitCause.LOW_MEMORY, classifyProcessExit(3, null))
        assertTrue(isReportable(ExitCause.LOW_MEMORY))
    }

    @Test
    fun `crashes and anr are reported`() {
        assertEquals(ExitCause.CRASH, classifyProcessExit(4, null))
        assertEquals(ExitCause.CRASH, classifyProcessExit(5, null))
        assertEquals(ExitCause.ANR, classifyProcessExit(6, null))
    }

    @Test
    fun `normal exits are not reported`() {
        listOf(0, 1, 10, 11, 16).forEach { reason ->
            assertFalse("reason=$reason", isReportable(classifyProcessExit(reason, null)))
        }
    }

    @Test
    fun `missing description is printed as a dash`() {
        val exit = ProcessExit(
            cause = ExitCause.CRASH,
            reason = 4,
            description = null,
            timestampMs = 1L,
            rssKb = 0L,
            pssKb = 0L,
        )
        assertTrue(exit.describe().endsWith("desc=-"))
    }

    private fun sampleExit(reason: Int, description: String?) = ProcessExit(
        cause = classifyProcessExit(reason, description),
        reason = reason,
        description = description,
        timestampMs = 1_700_000_000_000L,
        rssKb = 122_880L,
        pssKb = 100_352L,
    )
}
