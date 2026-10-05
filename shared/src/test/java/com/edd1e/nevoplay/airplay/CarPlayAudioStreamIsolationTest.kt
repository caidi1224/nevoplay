package com.edd1e.nevoplay.airplay

import java.io.Closeable
import java.net.Socket
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A CarPlay stream type is not an identity: music, guidance and Siri can run at the same time on
 * type 100. These tests pin the rule that only the same (type, audioType) pair replaces a stream, and
 * that a teardown of one type reports and closes every variant of it.
 */
class CarPlayAudioStreamIsolationTest {
    @Test
    fun guidanceOnTheSameTypeKeepsTheMusicStreamAlive() {
        val stopped = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(
            object : MediaSink {
                override fun onAudioStopped(id: AudioStreamId) {
                    stopped += id
                }
            },
        )
        val session = testSession()
        try {
            assertNotNull(engine.onAudio(session, 100, audioSetup("media")))
            val streams = streams(engine)
            val mediaKey = CarPlayMediaEngine.StreamKey(session, 100, "media")
            val music = streams[mediaKey]
            assertNotNull(music)

            // Guidance opens on the same type: the music stream must survive it.
            stopped.clear()
            assertNotNull(engine.onAudio(session, 100, audioSetup("default")))
            val guidanceKey = CarPlayMediaEngine.StreamKey(session, 100, "default")
            assertSame(music, streams[mediaKey])
            assertNotNull(streams[guidanceKey])
            assertEquals(2, streams.size)
            assertEquals(listOf(AudioStreamId(100, "default")), stopped)

            // Re-opening the same pair replaces that stream and only that stream; the audio type is
            // normalised, so "MEDIA" is the same stream as "media".
            val guidance = streams[guidanceKey]
            assertNotNull(engine.onAudio(session, 100, audioSetup("MEDIA")))
            assertNotSame(music, streams[mediaKey])
            assertSame(guidance, streams[guidanceKey])
            assertEquals(2, streams.size)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test
    fun typeTeardownClosesEveryVariantButKeepsOtherTypes() {
        val stopped = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(
            object : MediaSink {
                override fun onAudioStopped(id: AudioStreamId) {
                    stopped += id
                }
            },
        )
        val session = testSession()
        try {
            assertNotNull(engine.onAudio(session, 100, audioSetup("media")))
            assertNotNull(engine.onAudio(session, 100, audioSetup("default")))
            assertNotNull(engine.onAudio(session, 102, audioSetup("media")))

            stopped.clear()
            engine.onTeardown(session, 100)

            assertEquals(
                setOf(AudioStreamId(100, "media"), AudioStreamId(100, "default")),
                stopped.toSet(),
            )
            assertEquals(
                setOf(CarPlayMediaEngine.StreamKey(session, 102, "media")),
                streams(engine).keys.filter { it.audioType.isNotEmpty() }.toSet(),
            )
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    private fun audioSetup(audioType: String): Map<String, Any?> = mapOf(
        "audioType" to audioType,
        "audioFormat" to 0x8000L,
        "streamConnectionID" to 42L,
    )

    @Suppress("UNCHECKED_CAST")
    private fun streams(engine: CarPlayMediaEngine): MutableMap<CarPlayMediaEngine.StreamKey, Closeable> =
        CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
            .get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, Closeable>

    /**
     * A session whose pair-verify secret is set directly: the audio data key is derived from it, and
     * without one `onAudio` declines the stream before any of this is exercised.
     */
    private fun testSession(): AirPlaySession {
        val session = AirPlaySession(
            socket = Socket(),
            config = AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0",
                main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = AirPlayIdentity.generate(),
            pairings = PairingStore(),
            mfi = null,
            listener = object : AirPlaySessionListener {},
            media = object : AirPlayMediaHandler {},
        )
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, secret)
        return session
    }
}
