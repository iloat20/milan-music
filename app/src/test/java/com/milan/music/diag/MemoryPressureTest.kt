package com.milan.music.diag

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 档位取自 `ComponentCallbacks2`：RUNNING_MODERATE=5、RUNNING_LOW=10、
 * RUNNING_CRITICAL=15、UI_HIDDEN=20、BACKGROUND=40、MODERATE=60、COMPLETE=80。
 */
class MemoryPressureTest {

    @Test
    fun `leaving the foreground is the baseline sample`() {
        assertEquals(MemoryPressure.BASELINE, memoryPressureFor(20))
    }

    @Test
    fun `foreground pressure has two tiers`() {
        assertEquals(MemoryPressure.RUNNING_LOW, memoryPressureFor(10))
        assertEquals(MemoryPressure.RUNNING_CRITICAL, memoryPressureFor(15))
    }

    @Test
    fun `background pressure escalates with the level`() {
        assertEquals(MemoryPressure.BACKGROUND_LOW, memoryPressureFor(40))
        assertEquals(MemoryPressure.BACKGROUND_LOW, memoryPressureFor(60))
        assertEquals(MemoryPressure.CRITICAL, memoryPressureFor(80))
    }

    @Test
    fun `mild and unknown levels are not logged`() {
        listOf(0, 5, 99).forEach { level ->
            assertEquals("level=$level", MemoryPressure.NONE, memoryPressureFor(level))
        }
    }

    @Test
    fun `line carries heap and pss in whole megabytes`() {
        assertEquals(
            "mem pressure=baseline heap=42/192MB pss=118MB",
            memoryLine(
                pressure = MemoryPressure.BASELINE,
                heapUsedKb = 43_008L,
                heapMaxKb = 196_608L,
                pssKb = 121_344L,
            ),
        )
    }
}
