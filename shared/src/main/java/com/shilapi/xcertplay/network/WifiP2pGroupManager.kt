package com.shilapi.xcertplay.network

import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal data class WifiP2pCredentials(
    val ssid: String,
    val passphrase: String,
)

internal fun mfiCertificateWifiP2pCredentials(certificate: ByteArray): WifiP2pCredentials {
    require(certificate.isNotEmpty()) { "MFi certificate must not be empty" }
    val digest = MessageDigest.getInstance("SHA-1").digest(certificate)
    val digestHex = buildString(digest.size * 2) {
        for (byte in digest) {
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }
    return WifiP2pCredentials(
        ssid = WIFI_P2P_SSID_PREFIX + digestHex.take(MFI_CERTIFICATE_SSID_SUFFIX_LENGTH),
        passphrase = digestHex.takeLast(MFI_CERTIFICATE_PASSPHRASE_LENGTH),
    )
}

/**
 * Creates a temporary 5 GHz Wi-Fi Direct group owner that can also be joined as a legacy AP.
 *
 * The group is deliberately not persistent. [close] removes it and releases the callback thread.
 */
class WifiP2pGroupManager(
    context: Context,
    private val networkName: String,
    private val passphrase: String,
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    private val p2pManager = appContext.getSystemService(WifiP2pManager::class.java)
        ?: throw IllegalStateException("WifiP2pManager is unavailable")
    private val stateLock = Object()

    private var channel: WifiP2pManager.Channel? = null
    private var callbackThread: HandlerThread? = null
    private var created = false
    private var closed = false
    private var startAttempt: StartAttempt? = null

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IOException("Wi-Fi P2P credentials require Android 10 (API 29) or newer")
        }
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "WifiP2pGroupManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }

        val attempt = StartAttempt()
        synchronized(stateLock) {
            check(!closed) { "WifiP2pGroupManager is closed" }
            check(startAttempt == null && !created) {
                "A Wi-Fi P2P group is already starting or active"
            }
            startAttempt = attempt
        }

        val thread = HandlerThread("xcertplay-wifi-p2p").apply { start() }
        attempt.thread = thread
        val deadlineNanos = deadlineAfter(timeoutMillis)
        val credentials = WifiP2pCredentials(networkName, passphrase)

        try {
            val p2pChannel = p2pManager.initialize(
                appContext,
                thread.looper,
                createChannelListener(attempt),
            )
            attempt.channel = p2pChannel
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                channel = p2pChannel
                callbackThread = thread
            }

            val config = WifiP2pConfig.Builder()
                .setNetworkName(credentials.ssid)
                .setPassphrase(credentials.passphrase)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                config.groupOwnerIntent = WifiP2pConfig.GROUP_OWNER_INTENT_MAX
            }

            ensureStartActive(attempt)
            // A group left behind by a previous run - the app being killed without a chance to call
            // close() is enough - makes createGroup answer BUSY for about 45 seconds. Take that group
            // over when it is ours and already usable, clear it otherwise, and only then ask for one.
            val adopted = adoptPreviousGroup(
                attempt = attempt,
                channel = p2pChannel,
                credentials = credentials,
                deadlineNanos = deadlineNanos,
            )
            if (adopted == null) {
                createGroupWithBusyRetry(attempt, p2pChannel, config, deadlineNanos, timeoutMillis)
            }

            val group = adopted ?: awaitUsableGroup(
                attempt = attempt,
                channel = p2pChannel,
                credentials = credentials,
                deadlineNanos = deadlineNanos,
                timeoutMillis = timeoutMillis,
            )
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                created = true
                startAttempt = null
            }
            return group
        } catch (failure: Exception) {
            cleanupFailedStart(attempt)
            throw failure
        }
    }

    override fun close() {
        val attempt: StartAttempt?
        val activeChannel: WifiP2pManager.Channel?
        val activeThread: HandlerThread?
        val removeGroup: Boolean
        synchronized(stateLock) {
            if (closed) return
            closed = true
            attempt = startAttempt
            attempt?.stopped = true
            stateLock.notifyAll()
            activeChannel = channel ?: attempt?.channel
            activeThread = callbackThread ?: attempt?.thread
            removeGroup = created || attempt?.createSucceeded == true
            channel = null
            callbackThread = null
            startAttempt = null
        }

        if (removeGroup && activeChannel != null) {
            removeGroupBlocking(activeChannel)
        }
        activeThread?.quitSafely()
    }

    private fun createChannelListener(
        attempt: StartAttempt,
    ): WifiP2pManager.ChannelListener = object : WifiP2pManager.ChannelListener {
        override fun onChannelDisconnected() {
            failAttempt(attempt, IOException("Wi-Fi P2P channel disconnected"))
        }
    }

    private fun createActionListener(
        attempt: StartAttempt,
    ): WifiP2pManager.ActionListener = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            val activeChannel = attempt.channel
            val removeDetachedGroup = synchronized(stateLock) {
                attempt.createSucceeded = true
                created = true
                if (startAttempt === attempt && !attempt.stopped && !closed) {
                    stateLock.notifyAll()
                    false
                } else {
                    true
                }
            }
            if (removeDetachedGroup && activeChannel != null) {
                removeGroup(activeChannel, waitForCallback = false)
            }
        }

        override fun onFailure(reason: Int) {
            failAttempt(
                attempt,
                IOException("Wi-Fi P2P createGroup failed: ${failureReason(reason)}"),
                reason,
            )
        }
    }

    private fun failAttempt(attempt: StartAttempt, failure: IOException, reason: Int? = null) {
        synchronized(stateLock) {
            if (startAttempt === attempt && !attempt.stopped && !closed) {
                if (attempt.failure == null) attempt.failure = failure
                if (reason != null) attempt.createFailureReason = reason
                stateLock.notifyAll()
            }
        }
    }

    private fun awaitGroupCreated(
        attempt: StartAttempt,
        deadlineNanos: Long,
        timeoutMillis: Long,
    ) {
        synchronized(stateLock) {
            while (true) {
                ensureStartActiveLocked(attempt)
                attempt.failure?.let { throw it }
                if (attempt.createSucceeded) return

                val remainingNanos = remainingNanos(deadlineNanos)
                if (remainingNanos <= 0) {
                    throw IOException(
                        "Timed out after ${timeoutMillis}ms waiting for Wi-Fi P2P group creation",
                    )
                }
                waitNanos(remainingNanos)
            }
        }
    }

    /**
     * Returns a group left behind by a previous run when it is ours and already usable, and clears
     * whatever else is holding the P2P radio so that creating a group can succeed.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun adoptPreviousGroup(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        credentials: WifiP2pCredentials,
        deadlineNanos: Long,
    ): WirelessHotspotInfo? {
        val previous = requestGroupInfo(
            attempt = attempt,
            channel = channel,
            timeoutNanos = minOf(remainingNanos(deadlineNanos), REQUEST_POLL_NANOS),
        ) ?: return null
        val name = previous.networkName?.takeIf { it.isNotBlank() }
        if (name == credentials.ssid) {
            val probeDeadline = minOf(deadlineNanos, System.nanoTime() + GROUP_ADOPT_PROBE_NANOS)
            try {
                return awaitUsableGroup(
                    attempt = attempt,
                    channel = channel,
                    credentials = credentials,
                    deadlineNanos = probeDeadline,
                    timeoutMillis = GROUP_ADOPT_PROBE_MILLIS,
                )
            } catch (failure: IOException) {
                Log.w(TAG, "leftover Wi-Fi P2P group '$name' is unusable: ${failure.message}")
            }
        } else if (name != null && name.startsWith(WIFI_P2P_SSID_PREFIX)) {
            Log.w(TAG, "clearing the stale Wi-Fi P2P group '$name' left by an earlier session")
        } else {
            // A group this app did not create. Removing it would fight whichever app does own it, and
            // the radio is the scarce resource here, so report it instead of taking it away.
            throw IOException(
                "Wi-Fi Direct is held by another group (${name ?: "unnamed"}); " +
                    "close other projection apps and try again",
            )
        }
        if (!removeGroupAndConfirm(attempt, channel, deadlineNanos)) {
            Log.w(TAG, "Wi-Fi P2P group '$name' was still present after removal")
        }
        return null
    }

    /**
     * Creates the group, retrying once after clearing it when the framework answers BUSY - the
     * symptom of a group it is still holding on to.
     */
    private fun createGroupWithBusyRetry(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        config: WifiP2pConfig,
        deadlineNanos: Long,
        timeoutMillis: Long,
    ) {
        p2pManager.createGroup(channel, config, createActionListener(attempt))
        try {
            awaitGroupCreated(attempt, deadlineNanos, timeoutMillis)
            return
        } catch (failure: IOException) {
            if (synchronized(stateLock) { attempt.createFailureReason } != WifiP2pManager.BUSY) {
                throw failure
            }
            Log.w(TAG, "Wi-Fi P2P createGroup was busy; clearing the stale group and retrying once")
            removeGroupBlocking(channel)
            synchronized(stateLock) {
                ensureStartActiveLocked(attempt)
                attempt.failure = null
                attempt.createFailureReason = null
            }
        }
        p2pManager.createGroup(channel, config, createActionListener(attempt))
        awaitGroupCreated(attempt, deadlineNanos, timeoutMillis)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun awaitUsableGroup(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        credentials: WifiP2pCredentials,
        deadlineNanos: Long,
        timeoutMillis: Long,
    ): WirelessHotspotInfo {
        var lastReason = "group information was not available"
        while (true) {
            ensureStartActive(attempt)
            val remainingNanos = remainingNanos(deadlineNanos)
            if (remainingNanos <= 0) {
                throw IOException(
                    "Timed out after ${timeoutMillis}ms waiting for a usable Wi-Fi P2P group: " +
                        lastReason,
                )
            }

            val group = requestGroupInfo(
                attempt = attempt,
                channel = channel,
                timeoutNanos = minOf(remainingNanos, REQUEST_POLL_NANOS),
            )
            if (group == null) continue
            if (!group.isGroupOwner) {
                throw IOException("Wi-Fi P2P device became a group client instead of owner")
            }

            val networkName = group.networkName?.takeIf { it.isNotBlank() }
            val passphrase = group.passphrase?.takeIf { it.isNotBlank() }
                ?: credentials.passphrase
            val interfaceName = group.getInterface()?.takeIf { it.isNotBlank() }
            val frequencyMHz = group.frequency
            val channelNumber = wifiFrequencyMhzToChannel(frequencyMHz)
            if (
                networkName == null ||
                interfaceName == null ||
                frequencyMHz <= 0 ||
                channelNumber == null
            ) {
                lastReason = "networkName=$networkName interface=$interfaceName " +
                    "frequencyMHz=$frequencyMHz"
                continue
            }
            if (!is5Ghz(frequencyMHz)) {
                throw IOException(
                    "Wi-Fi P2P created the group at ${frequencyMHz}MHz instead of 5 GHz",
                )
            }

            val hostAddress = interfaceAddress(interfaceName)
                ?: requestConnectionAddress(
                    attempt = attempt,
                    channel = channel,
                    timeoutNanos = minOf(remainingNanos(deadlineNanos), REQUEST_POLL_NANOS),
                )
            if (hostAddress == null) {
                lastReason = "interface $interfaceName has no routable IPv4 or IPv6 address yet " +
                    "(a link-local address is not usable by the phone)"
                continue
            }
            Log.i(
                TAG,
                "Wi-Fi P2P group ready ssid=$networkName interface=$interfaceName " +
                    "address=${hostAddress.hostAddress} channel=$channelNumber",
            )

            return WirelessHotspotInfo(
                ssid = networkName,
                passphrase = passphrase,
                security = groupSecurity(group),
                channel = channelNumber,
                frequencyMHz = frequencyMHz,
                bssid = interfaceHardwareAddress(interfaceName)
                    ?: group.owner?.deviceAddress?.takeIf { it.isNotBlank() },
                interfaceName = interfaceName,
                hostAddress = hostAddress,
                bandLabel = "5 GHz",
                backend = WirelessHotspotBackend.WIFI_P2P,
            )
        }
    }

    private fun requestGroupInfo(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        timeoutNanos: Long,
    ): WifiP2pGroup? {
        val result = AtomicReference<WifiP2pGroup?>()
        val latch = CountDownLatch(1)
        p2pManager.requestGroupInfo(channel) {
            result.set(it)
            latch.countDown()
        }
        if (!await(latch, timeoutNanos)) return null
        ensureStartActive(attempt)
        return result.get()
    }

    private fun requestConnectionAddress(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        timeoutNanos: Long,
    ): InetAddress? {
        val result = AtomicReference<WifiP2pInfo?>()
        val latch = CountDownLatch(1)
        p2pManager.requestConnectionInfo(channel) {
            result.set(it)
            latch.countDown()
        }
        if (!await(latch, timeoutNanos)) return null
        ensureStartActive(attempt)
        val info = result.get() ?: return null
        if (!info.groupFormed) return null
        return info.groupOwnerAddress?.takeIf(::isAnnounceableAddress)
    }

    private fun await(latch: CountDownLatch, timeoutNanos: Long): Boolean = try {
        val waitNanos = timeoutNanos.coerceAtLeast(1L)
        latch.await(waitNanos, TimeUnit.NANOSECONDS)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        throw IOException("Interrupted while waiting for Wi-Fi P2P", interrupted)
    }

    /**
     * Returns the address the iPhone is told to reach us at.
     *
     * A bare link-local IPv6 address (`fe80::`) cannot be used by the phone across the P2P link, and
     * announcing one made the app report a hotspot as ready while the phone could never join - the
     * device log shows four starts that did exactly that. So IPv4 wins, a routable IPv6 is the
     * fallback, and waiting for one of those beats announcing something unreachable.
     */
    private fun interfaceAddress(interfaceName: String): InetAddress? {
        val networkInterface = networkInterface(interfaceName) ?: return null
        var routableIpv6: InetAddress? = null
        for (address in Collections.list(networkInterface.inetAddresses)) {
            if (!isAnnounceableAddress(address)) continue
            if (address is Inet4Address) return address
            if (address is Inet6Address && routableIpv6 == null) routableIpv6 = address
        }
        return routableIpv6
    }

    private fun isAnnounceableAddress(address: InetAddress): Boolean = when {
        address.isAnyLocalAddress || address.isLoopbackAddress -> false
        address is Inet4Address -> true
        // Link-local IPv6 has no scope outside this interface, so it must never be advertised.
        address is Inet6Address -> !address.isLinkLocalAddress
        else -> false
    }

    private fun interfaceHardwareAddress(interfaceName: String): String? =
        networkInterface(interfaceName)
            ?.hardwareAddress
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun networkInterface(interfaceName: String): NetworkInterface? = try {
        NetworkInterface.getByName(interfaceName)
    } catch (_: SocketException) {
        null
    }

    private fun groupSecurity(group: WifiP2pGroup): Iap2WirelessSecurity {
        if (Build.VERSION.SDK_INT < 36) return Iap2WirelessSecurity.WPA_WPA2
        return when (group.securityType) {
            WifiP2pGroup.SECURITY_TYPE_WPA2_PSK -> Iap2WirelessSecurity.WPA_WPA2
            WifiP2pGroup.SECURITY_TYPE_WPA3_COMPATIBILITY ->
                Iap2WirelessSecurity.WPA3_TRANSITION
            WifiP2pGroup.SECURITY_TYPE_WPA3_SAE -> Iap2WirelessSecurity.WPA3_ONLY
            else -> throw IOException(
                "Unsupported Wi-Fi P2P security type: ${group.securityType}",
            )
        }
    }

    private fun ensureStartActive(attempt: StartAttempt) {
        synchronized(stateLock) {
            ensureStartActiveLocked(attempt)
        }
    }

    private fun ensureStartActiveLocked(attempt: StartAttempt) {
        if (closed) throw IOException("WifiP2pGroupManager closed while starting")
        if (startAttempt !== attempt) throw IOException("Wi-Fi P2P startup was cancelled")
        if (attempt.stopped) throw IOException("Wi-Fi P2P group stopped before startup completed")
    }

    private fun cleanupFailedStart(attempt: StartAttempt) {
        val failedChannel: WifiP2pManager.Channel?
        val failedThread: HandlerThread?
        val removeGroup: Boolean
        synchronized(stateLock) {
            if (startAttempt === attempt) startAttempt = null
            attempt.stopped = true
            stateLock.notifyAll()
            failedChannel = attempt.channel
            failedThread = attempt.thread
            removeGroup = attempt.createSucceeded
            if (channel === failedChannel) channel = null
            if (callbackThread === failedThread) callbackThread = null
        }
        if (removeGroup && failedChannel != null) {
            removeGroupBlocking(failedChannel)
        }
        failedThread?.quitSafely()
    }

    private fun removeGroupBlocking(channel: WifiP2pManager.Channel) {
        removeGroup(channel, waitForCallback = true)
    }

    /**
     * Removes the group and then waits until the framework agrees it is gone.
     *
     * `removeGroup` only reports that the request was accepted; creating a new group before the old
     * one has actually been released is what the framework answers with BUSY. A device log showed 110
     * such failures over 124 seconds of blind retries, so this waits instead.
     */
    private fun removeGroupAndConfirm(
        attempt: StartAttempt,
        channel: WifiP2pManager.Channel,
        deadlineNanos: Long,
    ): Boolean {
        removeGroupBlocking(channel)
        val confirmDeadline = minOf(deadlineNanos, System.nanoTime() + REMOVE_GROUP_CONFIRM_NANOS)
        while (System.nanoTime() < confirmDeadline) {
            val remaining = requestGroupInfo(
                attempt = attempt,
                channel = channel,
                timeoutNanos = minOf(confirmDeadline - System.nanoTime(), REQUEST_POLL_NANOS),
            )
            if (remaining == null) {
                Log.i(TAG, "Wi-Fi P2P group removal confirmed")
                return true
            }
            Thread.sleep(REMOVE_GROUP_CONFIRM_POLL_MILLIS)
        }
        return false
    }

    private fun removeGroup(channel: WifiP2pManager.Channel, waitForCallback: Boolean) {
        val latch = CountDownLatch(1)
        try {
            p2pManager.removeGroup(
                channel,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        latch.countDown()
                    }

                    override fun onFailure(reason: Int) {
                        Log.w(TAG, "Wi-Fi P2P removeGroup failed: ${failureReason(reason)}")
                        latch.countDown()
                    }
                },
            )
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Wi-Fi P2P removeGroup could not be issued", failure)
            latch.countDown()
        }
        if (!waitForCallback) return
        try {
            latch.await(REMOVE_GROUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun waitNanos(nanos: Long) {
        val millis = nanos / NANOS_PER_MILLISECOND
        val remainder = (nanos % NANOS_PER_MILLISECOND).toInt()
        try {
            stateLock.wait(millis, remainder)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Interrupted while waiting for Wi-Fi P2P", interrupted)
        }
    }

    private fun deadlineAfter(timeoutMillis: Long): Long {
        val now = System.nanoTime()
        val delta = timeoutMillis * NANOS_PER_MILLISECOND
        return if (Long.MAX_VALUE - now < delta) Long.MAX_VALUE else now + delta
    }

    private fun remainingNanos(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L)

    private fun failureReason(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi P2P is unsupported"
        WifiP2pManager.BUSY -> "Wi-Fi P2P is busy"
        WifiP2pManager.ERROR -> "generic error"
        WifiP2pManager.NO_PERMISSION -> "permission denied"
        else -> "reason $reason"
    }

    private fun is5Ghz(frequencyMHz: Int): Boolean = frequencyMHz in 5150..5895

    private class StartAttempt {
        var channel: WifiP2pManager.Channel? = null
        var thread: HandlerThread? = null
        var createSucceeded = false
        var createFailureReason: Int? = null
        var failure: IOException? = null
        var stopped = false
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val REMOVE_GROUP_TIMEOUT_MILLIS = 2_000L
        /** How long to keep polling for confirmation that the old group has been released. */
        val REMOVE_GROUP_CONFIRM_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(2_000)
        const val REMOVE_GROUP_CONFIRM_POLL_MILLIS = 100L
        const val GROUP_ADOPT_PROBE_MILLIS = 500L
        val REQUEST_POLL_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(500)
        val GROUP_ADOPT_PROBE_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(GROUP_ADOPT_PROBE_MILLIS)
    }
}

private const val WIFI_P2P_SSID_PREFIX = "DIRECT-xcertplay"
private const val MFI_CERTIFICATE_SSID_SUFFIX_LENGTH = 4
private const val MFI_CERTIFICATE_PASSPHRASE_LENGTH = 8
private const val HEX_DIGITS = "0123456789abcdef"
