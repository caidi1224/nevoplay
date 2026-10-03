package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioModeLeaseManagerTest {

    /** Stands in for AudioManager.mode, including a device that refuses the write. */
    private class FakeAudioMode(
        var mode: Int,
        private val acceptsWrites: Boolean = true,
    ) {
        var writes = 0
            private set

        fun write(value: Int) {
            writes++
            if (acceptsWrites) mode = value
        }
    }

    @Test
    fun entersCommunicationModeAndRestoresThePreviousOne() {
        val audio = FakeAudioMode(NORMAL)
        val lease = manager(audio).acquire()

        assertEquals(COMMUNICATION, audio.mode)
        assertEquals(1, audio.writes)

        lease.close()
        assertEquals(NORMAL, audio.mode)
    }

    @Test
    fun leavesAnAlreadyActiveCommunicationModeAlone() {
        val audio = FakeAudioMode(COMMUNICATION)
        val lease = manager(audio).acquire()

        assertEquals(0, audio.writes)

        lease.close()
        assertEquals(COMMUNICATION, audio.mode)
    }

    /**
     * The case the device log shows on a call: the telephony stack owns the mode, so asking for
     * MODE_IN_COMMUNICATION neither succeeds nor is needed. The uplink has to start anyway - a
     * refused mode used to abort it and leave the other party with silence.
     */
    @Test
    fun capturesUnderTheCallModeTheTelephonyStackAlreadySet() {
        val audio = FakeAudioMode(IN_CALL)
        val degraded = mutableListOf<Int>()
        val lease = manager(audio, degraded = degraded).acquire()

        assertEquals(0, audio.writes)
        assertEquals(emptyList<Int>(), degraded)

        lease.close()
        assertEquals(IN_CALL, audio.mode)
    }

    @Test
    fun keepsGoingWhenEveryWriteIsRefused() {
        val audio = FakeAudioMode(NORMAL, acceptsWrites = false)
        val degraded = mutableListOf<Int>()
        val manager = manager(audio, degraded = degraded)

        // No exception: an imperfect route beats no uplink at all.
        val lease = manager.acquire()

        assertEquals(listOf(NORMAL), degraded)
        assertEquals(1, audio.writes)

        lease.close()
        // The mode was never ours, so there is nothing to restore.
        assertEquals(NORMAL, audio.mode)
    }

    /**
     * The line that was missing when AudioFlinger refused 12 of 14 attempts to open the call
     * microphone: the mode the capture actually ran under, so the refusal can be tied to a value.
     */
    @Test
    fun reportsTheModeItFoundAndWhatItDid() {
        val fromNormal = mutableListOf<String>()
        manager(FakeAudioMode(NORMAL), acquired = fromNormal).acquire().close()
        assertEquals(listOf("found=0 attemptedWrite=true effective=3"), fromNormal)

        // Already in the call mode: nothing written, and the value is what the capture ran under.
        val fromCall = mutableListOf<String>()
        manager(FakeAudioMode(IN_CALL), acquired = fromCall).acquire().close()
        assertEquals(listOf("found=2 attemptedWrite=false effective=2"), fromCall)
    }

    @Test
    fun holdsTheModeUntilTheLastLeaseCloses() {
        val audio = FakeAudioMode(NORMAL)
        val manager = manager(audio)
        val first = manager.acquire()
        val second = manager.acquire()

        assertEquals(1, audio.writes)
        first.close()
        assertEquals(COMMUNICATION, audio.mode)
        second.close()
        assertEquals(NORMAL, audio.mode)
    }

    private fun manager(
        audio: FakeAudioMode,
        degraded: MutableList<Int> = mutableListOf(),
        acquired: MutableList<String> = mutableListOf(),
    ) = AudioModeLeaseManager(
        readMode = { audio.mode },
        writeMode = audio::write,
        communicationMode = COMMUNICATION,
        acceptableModes = setOf(COMMUNICATION, IN_CALL),
        onAcquired = { found, attemptedWrite, effective ->
            acquired += "found=$found attemptedWrite=$attemptedWrite effective=$effective"
        },
        onDegraded = { degraded += it },
    )

    private companion object {
        const val NORMAL = 0
        const val IN_CALL = 2
        const val COMMUNICATION = 3
    }
}
