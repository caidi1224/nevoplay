package com.edd1e.nevoplay

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.edd1e.nevoplay.airplay.AirPlayConfig
import com.edd1e.nevoplay.airplay.AirPlayDisplaySettings
import com.edd1e.nevoplay.airplay.AirPlayPhysicalSizeBasis
import com.edd1e.nevoplay.airplay.AirPlayPhysicalSizeMm
import com.edd1e.nevoplay.airplay.CarPlayDisplayScale
import com.edd1e.nevoplay.airplay.AirPlayDisplayConfig
import com.edd1e.nevoplay.airplay.AirPlayIdentity
import com.edd1e.nevoplay.airplay.AirPlayIcon
import com.edd1e.nevoplay.airplay.AirPlaySafeArea
import com.edd1e.nevoplay.airplay.AirPlaySession
import com.edd1e.nevoplay.airplay.AirPlaySessionListener
import com.edd1e.nevoplay.airplay.CarPlayMediaEngine
import com.edd1e.nevoplay.airplay.SafeAreaRect
import com.edd1e.nevoplay.host.R
import com.edd1e.nevoplay.location.AndroidCarPlayLocationProvider
import com.edd1e.nevoplay.media.AndroidMediaSink
import com.edd1e.nevoplay.media.CarPlayTouchMapper
import com.edd1e.nevoplay.media.MediaMetricsMonitor
import com.edd1e.nevoplay.media.MainMediaAudioBuffer
import com.edd1e.nevoplay.media.MicrophoneGain
import com.edd1e.nevoplay.media.MicrophoneLevelMonitor
import com.edd1e.nevoplay.mfi.LocalMfiDocuments
import com.edd1e.nevoplay.network.CarPlayVpnService
import com.edd1e.nevoplay.orchestration.CarPlayController
import com.edd1e.nevoplay.orchestration.CarPlayRuntimeConfig
import com.edd1e.nevoplay.orchestration.CarPlayStatus
import com.edd1e.nevoplay.orchestration.CarPlayTransport
import com.edd1e.nevoplay.orchestration.ManualHotspotBand
import com.edd1e.nevoplay.orchestration.ManualHotspotSecurity
import com.edd1e.nevoplay.orchestration.MfiTarget
import com.edd1e.nevoplay.orchestration.WirelessHotspotMode
import com.edd1e.nevoplay.orchestration.isManualHotspotChannelCompatible
import com.edd1e.nevoplay.orchestration.keepsHotspotAcrossRebuild
import com.edd1e.nevoplay.transport.Iap2IdentificationConfig
import com.edd1e.nevoplay.transport.Iap2LocationProvider
import com.edd1e.nevoplay.transport.UsbDeviceId
import com.edd1e.nevoplay.ui.HostBlock
import com.edd1e.nevoplay.ui.HostSlider
import com.edd1e.nevoplay.ui.HostToggle
import com.edd1e.nevoplay.ui.HostUi
import com.edd1e.nevoplay.ui.applyHostScale
import com.edd1e.nevoplay.ui.hostRow
import com.edd1e.nevoplay.host.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen CarPlay host. It renders decoded video through a [TextureView], forwards touch to
 * the active AirPlay session, and drives the complete wired or wireless bring-up through
 * [CarPlayController].
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity() {
    private data class SettingsBaseline(
        val safeAreaSize: DisplaySize?,
        val safeAreaRect: SafeAreaRect?,
        val customIconBytes: ByteArray?,
    )

    private lateinit var airPlayIdentity: AirPlayIdentity

    // CH341 USB\VID_1A86&PID_5512&REV_0304 is the deployment-supplied bridge identity.
    private fun createRuntimeConfig(): CarPlayRuntimeConfig = CarPlayRuntimeConfig(
        mfiTarget = mfiTarget,
        ch341Devices = if (mfiTarget == MfiTarget.USB_CH341) {
            listOf(UsbDeviceId(0x1a86, 0x5512))
        } else {
            emptyList()
        },
        // The CP latches its I2C address from the RST level at its own power-up, so the host must
        // not pulse RST before discovery. Driving D0 re-latches the part onto the alternate
        // address (0x10), where the accessory certificate is not readable. Leave RST at its
        // hardware pull (VCC -> 0x11) and let the scanner find the part with its certificate.
        // Set this back to 0 to restore the D0 pulse.
        ch341MfiResetGpio = null,
        linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
        remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
        remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
        localMfiCertificateUri = localMfiCertificateUri.takeIf { it.isNotEmpty() },
        localMfiPrivateKeyUri = localMfiPrivateKeyUri.takeIf { it.isNotEmpty() },
        identification = Iap2IdentificationConfig(
            name = "NEVOPlay",
            modelIdentifier = normalizedModel(),
            manufacturer = normalizedManufacturer(),
            serialNumber = "NEVO-A07",
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            carPlayUsbInterfaceNumber = 3,
            locationInformationEnabled = locationReportingEnabled,
        ),
        transport = if (wirelessEnabled) CarPlayTransport.WIRELESS else CarPlayTransport.WIRED,
        wirelessHotspotMode = wirelessHotspotMode,
        manualHotspotSsid = manualHotspotSsid,
        manualHotspotPassphrase = manualHotspotPassphrase,
        manualHotspotBand = manualHotspotBand,
        manualHotspotChannel = manualHotspotChannel,
        manualHotspotSecurity = manualHotspotSecurity,
        locationReportingEnabled = locationReportingEnabled,
    )

    private val vpnConsent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            awaitingVpnConsent = false
            if (result.resultCode == RESULT_OK) {
                vpnReady = true
                maybeStartCarPlay()
            } else {
                setStatus("VPN consent was denied")
            }
        }
    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingWirelessPermissions = false
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted"
                } else {
                    "Wireless startup permissions denied"
                },
            )
            updateHotspotStatusBlock()
            maybeStartCarPlay()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            val startGainTest = granted && microphoneGainTestAfterPermission && menuOpen
            microphoneGainTestAfterPermission = false
            requestStartupPrerequisites()
            if (startGainTest) startMicrophoneGainTest()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                locationReportingEnabled = false
                if (!menuOpen) {
                    AirPlayPersistence.saveLocationReportingEnabled(
                        this@CarPlayHostActivity,
                        false,
                    )
                }
                locationReportingSwitch?.isChecked = false
                val approximateOnly =
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                appendLog(
                    if (approximateOnly) {
                        "Precise location permission denied; location reporting disabled"
                    } else {
                        "Location permission denied; location reporting disabled"
                    },
                )
            }
            updateResolutionMenu()
            if (!menuOpen) requestStartupPrerequisites()
        }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) {
                externalActivityInProgress = false
                return@registerForActivityResult
            }
            imageCrop.launch(
                Intent(this, ImageCropActivity::class.java)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        }
    private val imageCrop =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            externalActivityInProgress = false
            if (result.resultCode == RESULT_OK) {
                updateAirPlayIconPreview()
                appendLog("Custom AirPlay icon updated")
            }
        }
    private val localMfiCertificatePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            externalActivityInProgress = false
            if (uri != null && retainDocumentReadPermission(uri, "certificate")) {
                localMfiCertificateUri = uri.toString()
                updateLocalMfiDocumentViews()
                updateLocalMfiStatus()
                mfiErrorView?.visibility = View.GONE
            }
        }
    private val localMfiPrivateKeyPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            externalActivityInProgress = false
            if (uri != null && retainDocumentReadPermission(uri, "private key")) {
                localMfiPrivateKeyUri = uri.toString()
                updateLocalMfiDocumentViews()
                updateLocalMfiStatus()
                mfiErrorView?.visibility = View.GONE
            }
        }

    private var videoView: TextureView? = null
    private var gestureOverlay: View? = null
    private var idlePanel: View? = null
    /** Covers the last decoded frame whenever no screen stream is active. */
    private var videoBackdropView: View? = null
    /** Set while the link has gone quiet: the picture on screen is stale, not live. */
    private var linkSilent = false
    private var idleTransportValue: TextView? = null
    private var idleHotspotValue: TextView? = null
    private var idleMfiValue: TextView? = null
    private var idleLogToggle: HostToggle? = null
    /** The "Connect to iPhone" row, shown whenever a fresh handshake is worth offering. */
    private var reconnectCommandRow: View? = null
    /** The reconnect that is waiting out its backoff, so a tap can replace the wait. */
    private var pendingReconnect: Runnable? = null
    private var contentRoot: View? = null
    private var idleTitleBasePx = 0f

    /** Top inset of the vehicle's own status bar while it is drawn over our content. */
    private var statusBarTopInsetPx = 0
    private var settingsContentPadding: IntArray? = null
    private var settingsContentView: View? = null
    private var settingsHeaderView: View? = null
    private var settingsLiveView: TextView? = null
    private var settingsHeaderPadding: IntArray? = null
    private var settingsMenu: View? = null

    /** The category block the settings column is currently filling; see [buildSettingsMenu]. */
    private var settingsBlock: HostBlock? = null
    private var mfiTargetGroup: RadioGroup? = null
    private var mfiI2cFields: View? = null
    private var mfiRemoteFields: View? = null
    private var mfiLocalFields: View? = null
    private var mfiErrorView: TextView? = null
    private var mfiI2cPathInput: EditText? = null
    private var remoteMfiServerInput: EditText? = null
    private var remoteMfiTokenInput: EditText? = null
    private var localMfiCertificateDocumentView: TextView? = null
    private var localMfiPrivateKeyDocumentView: TextView? = null
    private var localMfiStatusView: TextView? = null
    private var mediaMetricsOverlay: MediaMetricsOverlayView? = null
    private var mediaMetricsMonitor: MediaMetricsMonitor? = null
    private var settingsBaseline: SettingsBaseline? = null
    private var locationReportingSwitch: HostToggle? = null
    private var microphoneGainSeekBar: HostSlider? = null
    private var microphoneGainValueView: TextView? = null
    private var mainMediaAudioBufferSlider: HostSlider? = null
    private var mainMediaAudioBufferValueView: TextView? = null
    private var microphoneTestButton: TextView? = null
    private var microphoneLevelBar: ProgressBar? = null
    private var microphoneLevelValueView: TextView? = null
    private var microphoneLevelMonitor: MicrophoneLevelMonitor? = null
    private var microphoneGainTestAfterPermission = false
    private var statusView: TextView? = null
    private var statusScrollView: ScrollView? = null
    private var stageStatusView: TextView? = null
    private var resolutionValueView: TextView? = null
    private var resolutionPreviewView: TextView? = null
    private var hotspotStatusView: TextView? = null
    private var manualHotspotFields: View? = null
    private var manualHotspotErrorView: TextView? = null
    private var iconPreviewView: ImageView? = null
    private var iconStatusView: TextView? = null
    private var safeAreaSummaryView: TextView? = null
    private var safeAreaEditor: View? = null
    private var safeAreaEditorView: SafeAreaEditorView? = null
    private var safeAreaEditSize: DisplaySize? = null
    private var safeAreaEditorActive = false
    private var externalActivityInProgress = false
    private var sink: AndroidMediaSink? = null
    private var controller: CarPlayController? = null
    private var currentSurface: Surface? = null
    private var currentSurfaceTexture: SurfaceTexture? = null
    private var activeDisplaySize: DisplaySize? = null
    private var pendingDisplaySize: DisplaySize? = null
    private var lastFullscreenRequestUptime = 0L
    private var systemBarOverrideStrikes = 0

    /** Set when the vehicle forces its own bar back right after we hid it, so we stop fighting. */
    private var vehicleEnforcesSystemBars = false
    private var displayRevertStrikes = 0
    private var lastDisplayRevertUptime = 0L

    /** While now is before this the app stops asking for fullscreen: the window keeps flipping. */
    private var displayYieldUntilUptime = 0L
    private var displayYieldSkips = 0
    private var fullscreenReassertCount = 0
    private var lastReassertLogUptime = 0L
    private var displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
    private var hevcEnabled = true
    private var hevcSoftwareDecoderEnabled = false
    private var advancedAudioChannelMappingSupported = false
    private var advancedAudioChannelMapping = false
    @Volatile private var debugLogsEnabled = false
    private var audioPacketCaptureEnabled = false
    private var mediaMetricsEnabled = false
    private var lastStageOverlayShown: Boolean? = null
    private val recentSessionMessages = ArrayDeque<String>()
    private val RECENT_SESSION_MESSAGE_LIMIT = 256
    private val TOUCH_STATS_WINDOW_MILLIS = 5_000L
    private var logcatTap: LogcatTap? = null
    private var buildLabelView: TextView? = null
    // Correlates stutter spikes with touch/HID bursts: both are timestamped, so a window with
    // many reports can be compared against the decoder's output-age spikes.
    private var touchReportsSinceStats = 0
    private var logLinesSinceStats = 0
    private var touchStatsPosted = false
    private var moreGesturesToSettings = false
    private var autoStartOnBoot = false
    private var manufacturer = AirPlayPersistence.DEFAULT_MANUFACTURER
    private var model = AirPlayPersistence.DEFAULT_MODEL
    private var oemLabel = AirPlayPersistence.DEFAULT_OEM_LABEL
    private var fps = AirPlayDisplaySettings.DEFAULT_FPS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM
    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    private var maximumDetectedWidthPixels = 0
    private var maximumDetectedHeightPixels = 0
    private var rightHandDrive = false
    private var hideTopBar = false
    private var hideBottomBar = false
    private var safeAreaDrawOutside = true
    private var locationReportingEnabled = false
    private var locationPermissionAvailable = false
    private var microphoneAvailable = false
    private var microphonePermissionResolved = false
    @Volatile private var microphoneGainPercent = MicrophoneGain.DEFAULT_PERCENT
    private var mainMediaAudioBufferDurationMs = MainMediaAudioBuffer.DEFAULT_DURATION_MS
    private var wirelessEnabled = false
    private var mfiTarget = MfiTarget.USB_CH341
    private var mfiI2cPath = AirPlayPersistence.DEFAULT_MFI_I2C_PATH
    private var remoteMfiServer = ""
    private var remoteMfiToken = ""
    private var localMfiCertificateUri = ""
    private var localMfiPrivateKeyUri = ""
    private var wirelessPermissionsReady = false
    private var wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P
    private var manualHotspotSsid = ""
    private var manualHotspotPassphrase = ""
    private var manualHotspotBand = ManualHotspotBand.AUTO
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity = ManualHotspotSecurity.OPEN
    private var awaitingVpnConsent = false
    private var awaitingWirelessPermissions = false
    private var awaitingLocationPermission = false
    private var vpnReady = false
    private var hotspotStatus = HotspotStatus(state = "off")
    private var menuOpen = false
    private var latestStage = "Preparing CarPlay"
    private var darkMode = false
    private var activeAirPlaySession: AirPlaySession? = null
    private val activeScreenStreamTypes = mutableSetOf<Int>()

    /** The window size the running controller negotiated, to notice a resize that happened unseen. */
    private var controllerDisplaySize: DisplaySize? = null

    /** True from the moment a rebuild starts until its new controller is running. */
    private var stackRebuildInProgress = false
    private var restartGeneration = 0
    private var reconnectScheduled = false
    /** Attempts to rebuild the stack that have failed in a row; reset when a session comes up. */
    private var consecutiveReconnectFailures = 0
    /** Set once the driver has been told that Wi-Fi P2P is stuck, so it is said once. */
    private var hotspotTroubleReported = false
    private var sessionLog: SessionLogFile? = null
    private var sessionLogDestination = ""
    private var gestureTapSwallowed = false
    private var gestureTapCandidate = false
    private var gestureTapStartX = 0f
    private var gestureTapStartY = 0f
    private var gestureTapDownTime = 0L
    private var gestureTapsSeen = 0
    private var gestureTapLastUpTime = 0L
    private var edgeSettingsGestureCaptured = false
    private var edgeSettingsGestureEligible = false
    private val shuttingDown = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val airPlayCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val logLines = ScreenLogBuffer(MAX_SCREEN_LOG_LINES)
    private val pendingScreenLogs = ArrayDeque<PendingLog>()
    private val pendingScreenLogsLock = Any()
    private var screenDrainPosted = false
    private val drainScreenLogs = Runnable {
        val pending = synchronized(pendingScreenLogsLock) {
            screenDrainPosted = false
            pendingScreenLogs.toList().also { pendingScreenLogs.clear() }
        }
        if (debugLogsEnabled && !menuOpen) {
            pending.forEach { entry ->
                if (entry.generation == restartGeneration) {
                    appendScreenLog(entry.timestampMillis, entry.message)
                }
            }
        }
    }
    private val expireOldLogLines = Runnable { refreshLogView(System.currentTimeMillis()) }
    private var logRenderScheduled = false
    private val renderLogLines = Runnable {
        logRenderScheduled = false
        refreshLogView(System.currentTimeMillis())
    }
    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            val existing = currentSurface
            val surface = if (
                existing != null &&
                currentSurfaceTexture === texture &&
                existing.isValid
            ) {
                existing
            } else {
                Surface(texture).also {
                    existing?.release()
                    currentSurface = it
                    currentSurfaceTexture = texture
                }
            }
            appendLog(if (existing === surface) "Texture surface reused" else "Texture surface created")
            attachSurface(surface)
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
            scheduleDisplaySize(width, height)
        }

        override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
            if (currentSurfaceTexture !== texture) return true
            currentSurface?.let { surface ->
                sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
                sink?.clearSurface(SCREEN_TYPE_ALT, surface)
                surface.release()
            }
            currentSurface = null
            currentSurfaceTexture = null
            appendLog("Texture surface destroyed")
            return true
        }

        override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initializeSessionLog()
        // A head unit that reports UI_MODE_NIGHT_UNDEFINED is not asking for the light palette; start
        // dark in that case, which is also what the panel looked like before the first explicit hint.
        darkMode = nightModeOrNull(resources.configuration.uiMode) ?: true
        HostUi.useDarkTheme(darkMode)
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        val root = buildContentView()
        setContentView(root)
        installSystemBarObserver(root)
        applyFullscreenMode()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (menuOpen) {
                        if (safeAreaEditorActive) closeSafeAreaEditor() else cancelSettingsEdits()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            },
        )

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; " +
                "transport=${if (wirelessEnabled) "wireless" else "wired"}",
        )
        val reusedBackgroundSession = adoptBackgroundSession()
        microphoneAvailable =
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphonePermissionResolved = microphoneAvailable
        if (reusedBackgroundSession) {
            updateDebugOverlays()
        } else if (microphonePermissionResolved) {
            requestStartupPrerequisites()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun loadPersistedSettings() {
        displayScaleTenths = AirPlayPersistence.loadDisplayScaleTenths(this)
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                AirPlayPersistence.loadHevcSoftwareDecoderEnabled(this)
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        microphoneGainPercent = AirPlayPersistence.loadMicrophoneGainPercent(this)
        mainMediaAudioBufferDurationMs = AirPlayPersistence.loadMainMediaAudioBufferDurationMs(this)
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
        audioPacketCaptureEnabled = AirPlayPersistence.loadAudioPacketCaptureEnabled(this)
        mediaMetricsEnabled = AirPlayPersistence.loadMediaMetricsEnabled(this)
        moreGesturesToSettings = AirPlayPersistence.loadMoreGesturesToSettings(this)
        autoStartOnBoot = AirPlayPersistence.loadAutoStartOnBoot(this)
        manufacturer = AirPlayPersistence.loadManufacturer(this)
        model = AirPlayPersistence.loadModel(this)
        oemLabel = AirPlayPersistence.loadOemLabel(this)
        fps = AirPlayPersistence.loadFps(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        hideTopBar = AirPlayPersistence.loadHideTopBar(this)
        hideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        locationPermissionAvailable = hasFineLocationPermission()
        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        localMfiCertificateUri = AirPlayPersistence.loadLocalMfiCertificateUri(this)
        localMfiPrivateKeyUri = AirPlayPersistence.loadLocalMfiPrivateKeyUri(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
    }

    private fun requestStartupPrerequisites() {
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        if (wirelessEnabled) {
            requestWirelessPermissions()
        } else {
            requestVpnConsent()
        }
    }

    private fun requestLocationPermission() {
        if (locationPermissionAvailable || awaitingLocationPermission) return
        awaitingLocationPermission = true
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestVpnConsent() {
        val consent = CarPlayVpnService.prepare(this)
        if (consent == null) {
            vpnReady = true
            maybeStartCarPlay()
        } else {
            awaitingVpnConsent = true
            vpnConsent.launch(consent)
        }
    }

    private fun requestWirelessPermissions() {
        val permissions = requiredWirelessPermissions()
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            wirelessPermissionsReady = true
            updateHotspotStatusBlock()
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        updateHotspotStatusBlock()
        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requiredWirelessPermissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    override fun onResume() {
        super.onResume()
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable && !menuOpen) {
            requestLocationPermission()
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        maybeStartCarPlay()
        reassertFullscreenIfNeeded()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Deliberately no fullscreen re-assert here. A head unit that pops its own status bar can take
        // focus while doing so, which turned every appearance of that bar into another hide request of
        // ours and made the window oscillate between two sizes. onResume still covers coming back from
        // another app, and the Settings switches still assert the user's choice.
    }

    override fun onStop() {
        stopMicrophoneGainTest()
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Null means the vehicle did not state a night mode in this update: keep the palette already
        // on screen instead of reading "undefined" as daylight.
        val statedNightMode = nightModeOrNull(newConfig.uiMode)
        val nextDarkMode = statedNightMode ?: darkMode
        // Recorded on every dispatch, including the ones that change nothing. Whether the vehicle
        // reports its day/night switch at all is the one fact that separates "the phone ignored our
        // setNightMode" from "we never knew about it" - and a config update that arrives carrying no
        // stated mode is exactly the second case. The raw uiMode goes in too, because which bits this
        // vehicle sets is not something to keep guessing at.
        appendLog(
            "uiMode uiMode=${newConfig.uiMode} stated=${statedNightMode ?: "undefined"} " +
                "paletteWas=${if (darkMode) "dark" else "light"} " +
                "applying=${if (nextDarkMode) "dark" else "light"}",
        )
        if (nextDarkMode != darkMode) {
            darkMode = nextDarkMode
            syncAirPlayDarkMode()
            applyTheme(rebuildViews = true)
        }
        // Deliberately no applyFullscreenMode() here. A head unit that insists on showing its own
        // status bar (typically as soon as the car is in gear) reports a new screen size, and
        // re-hiding the bar on every such change turns into a fight: the vehicle shows it again,
        // the window flips back, and each flip used to trigger another CarPlay re-handshake. The
        // hide request made at startup/resume stays in effect, so the bar simply follows whatever
        // the vehicle allows; fullscreen is re-asserted by onResume, onWindowFocusChanged, the
        // Settings switches and the baseline restore.
        stageStatusView?.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        updateIdleTitleSize()
        applyBarInsets()
        scrollLogsToBottom()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onDestroy() {
        stopMicrophoneGainTest()
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(renderLogLines)
        mainHandler.removeCallbacks(drainScreenLogs)
        currentSurface?.let { surface ->
            sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
            sink?.clearSurface(SCREEN_TYPE_ALT, surface)
            surface.release()
        }
        currentSurface = null
        currentSurfaceTexture = null
        sessionLog?.append("Activity destroyed")
        logcatTap?.close()
        logcatTap = null
        sessionLog?.close()
        sessionLog = null
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(NO_VIDEO_BACKGROUND)
        }
        val video = TextureView(this).apply {
            isOpaque = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            surfaceTextureListener = textureListener
        }
        // The single place every resize lands, whatever caused it: a vehicle status bar, a rotation,
        // an HDMI renegotiation or a settings change. Watching the surface itself keeps the CarPlay
        // resolution in step with the space actually available on every API level.
        video.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            if (width != oldRight - oldLeft || height != oldBottom - oldTop) {
                scheduleDisplaySize(width, height)
            }
        }
        val gestureLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { view, event -> onHostTouch(view, event) }
        }
        root.addView(video)
        root.addView(
            gestureLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // Opaque, and above the picture but below the idle panel. A TextureView keeps its last
        // frame, so the panel alone leaves the driver looking at a frozen CarPlay image: a device log
        // shows the banner being shown on every disconnect (11:56, 15:10, 16:04), which is not what
        // "the app is stuck on the CarPlay screen" looks like from the seat. Nothing else draws here,
        // so covering it is enough - no need to touch the surface the phone is negotiating with.
        val videoBackdrop = View(this).apply { setBackgroundColor(MENU_BACKGROUND) }
        root.addView(
            videoBackdrop,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        videoBackdropView = videoBackdrop
        contentRoot = root
        // The metrics overlay is a third of the panel and does not re-lay-out itself on a resize.
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                updateMediaMetricsOverlayLayout(right - left, bottom - top)
            }
        }
        videoView = video
        gestureOverlay = gestureLayer
        buildThemedOverlays(root)
        return root
    }

    /** Everything that has to be rebuilt when the palette changes; the video surface is not one. */
    private val themedOverlays = mutableListOf<View>()

    /**
     * Builds the views whose colours come from the palette - log, idle panel, build stamp, settings
     * and the safe-area editor - and attaches them to [root]. Kept separate from the video surface
     * so a theme change can rebuild them without touching the CarPlay session.
     */
    private fun buildThemedOverlays(root: ViewGroup) {
        val log = TextView(this).apply {
            setTextColor(HostUi.TEXT)
            setPadding(dp(20), dp(16), dp(20), dp(16))
            textSize = 15f
            typeface = HostUi.mono()
            text = ""
        }
        val logScroll = object : ScrollView(this) {
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean = false
            override fun onTouchEvent(event: MotionEvent): Boolean = false
        }.apply {
            background = HostUi.rounded(
                this@CarPlayHostActivity,
                10,
                HostUi.LOG_SCRIM,
                HostUi.LINE,
            )
            isFillViewport = false
            isFocusable = false
            addView(
                log,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> scrollLogsToBottom() }
        }
        // One piece of large type per screen: the current stage. It is only on screen while no
        // video stream is active, so the CarPlay picture never competes with it.
        val stageStatus = TextView(this).apply {
            setTextColor(HostUi.TEXT)
            textSize = IDLE_TITLE_TEXT_SP
            typeface = HostUi.monoBold()
            includeFontPadding = false
            letterSpacing = -0.02f
            maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            // Two lines are reserved whether the message needs them or not. The stage is the only
            // thing between the wordmark and the Settings row, so a message that wrapped onto a
            // second line used to push that row down and the next one pulled it back up.
            minLines = IDLE_TITLE_MIN_LINES
            maxLines = IDLE_TITLE_MAX_LINES
            ellipsize = TextUtils.TruncateAt.END
            text = latestStage
        }
        val idle = buildIdlePanel(stageStatus).apply { visibility = View.GONE }
        val statusParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START,
        )
        statusParams.setMargins(dp(48), 0, dp(48), dp(40))
        val idleParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        ).apply {
            val side = panelEdgePadding()
            setMargins(side, (side * 0.9f).toInt(), side, 0)
        }
        // Bottom-right corner: which build this is, so a head unit in the car can be identified
        // without pulling a log. Kept non-clickable so touches reach the video underneath.
        val buildLabel = TextView(this).apply {
            text = "v${BuildConfig.APP_VERSION} · ${BuildConfig.BUILD_DATE}"
            textSize = 16f
            typeface = HostUi.mono()
            setTextColor(HostUi.FAINT)
            isClickable = false
            isFocusable = false
        }
        val buildLabelParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(dp(48), 0, dp(48), dp(40)) }

        val settings = buildSettingsMenu().apply { visibility = View.GONE }
        val editor = buildSafeAreaEditor().apply { visibility = View.GONE }

        root.addView(logScroll, statusParams)
        root.addView(idle, idleParams)
        root.addView(buildLabel, buildLabelParams)
        buildLabelView = buildLabel
        root.addView(
            settings,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        idlePanel = idle
        settingsMenu = settings
        safeAreaEditor = editor
        statusView = log
        statusScrollView = logScroll
        stageStatusView = stageStatus
        themedOverlays += listOf(logScroll, idle, buildLabel, settings, editor)
        updateDebugOverlays()
    }

    /**
     * The vehicle decides the theme, and it can change it mid-session (night falls, the headlights
     * come on, or the driver picks light in the head unit's own settings). Rebuilding the overlays
     * is cheap and keeps the picture untouched; only the redraw changes colour.
     */
    private fun applyTheme(rebuildViews: Boolean) {
        HostUi.useDarkTheme(darkMode)
        // The root's background was set once, when the content view was built; without this the
        // picture area keeps the old theme's colour while everything on top of it changes.
        contentRoot?.setBackgroundColor(MENU_BACKGROUND)
        applySystemBarPalette()
        if (rebuildViews) {
            val root = contentRoot as? ViewGroup ?: return
            val editorWasOpen = safeAreaEditorActive
            if (editorWasOpen) closeSafeAreaEditor()
            themedOverlays.forEach { root.removeView(it) }
            themedOverlays.clear()
            buildThemedOverlays(root)
            if (editorWasOpen) openSafeAreaEditor()
        }
    }

    /**
     * Bars and window background only - no hide or show request, so this can run on a theme change
     * without joining the fight over who owns the bars.
     *
     * The bars are black in both themes, and that is not a leftover: the icon colour is the half of
     * this pair that cannot be moved on this vehicle, so the background has to follow the icons
     * rather than the palette.
     *
     * What was measured on 启源OS 2.2: the window's status bar colour is honoured at runtime (with
     * the light palette the bar really did come up off-white), but neither way of asking for dark
     * icons is. The deprecated `View.setSystemUiVisibility` flag that androidx's compatibility
     * wrapper writes when it is built from a Window is ignored, and so is the platform's own
     * `WindowInsetsController.setSystemBarsAppearance` on API 30+ - that was tried, and the light
     * theme still came up with white icons. The theme's `windowLightStatusBar=false` is therefore
     * the only value in play, and a theme cannot follow the palette here: `uiMode` is in
     * `configChanges`, so the activity is never recreated and the theme is read once.
     *
     * Light icons need a dark bar in every theme. `Theme.NEVOPlay` already declares exactly that,
     * so this keeps the declared and applied values in agreement. The light palette still paints
     * everything the app draws itself.
     */
    @Suppress("DEPRECATION")
    private fun applySystemBarPalette() {
        // The bars are black in both themes, and that is measured rather than assumed.
        //
        // Following the palette was tried and observed on the vehicle: with a white bar requested and
        // dark icons asked for through both APIs, the bar really did come up white and the icons
        // stayed white - unreadable, which is the defect this whole area exists to avoid. So the two
        // halves are not symmetric. The background is a window property and is honoured; the icon
        // tint arrives at the vehicle's own insets controller (it logs this app's call as
        // setSystemBarsAppearance: appearance=0, mask=24, down to the bits) and is then not rendered.
        // Requesting dark icons is therefore worse than useless here, and nothing the app can read
        // would tell it so.
        //
        // Light icons need a dark bar in every theme, and the theme cannot follow the palette anyway:
        // uiMode is in configChanges, so the activity is never recreated.
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.setBackgroundColor(Color.BLACK)
        val lightIcons = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(0, lightIcons)
        }
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        logSystemBarOwnership()
    }

    /**
     * One line that says who owns the status bar the driver can actually see.
     *
     * Two attempts at making that bar readable in the light palette failed, and both times the log
     * could not say why: nothing recorded whether the bar belongs to this window at all. If the bars
     * are not visible to this window while the driver still sees one, the vehicle draws it and no
     * colour or icon appearance set here can reach it. The panel being taller than the area Android
     * reports says the same thing from the other side - that strip is outside this app's display.
     */
    @Suppress("DEPRECATION")
    private fun logSystemBarOwnership() {
        val insets = ViewCompat.getRootWindowInsets(window.decorView)
        val statusBarColor = window.statusBarColor
        val activeDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            windowManager.defaultDisplay
        }
        val mode = activeDisplay?.mode
        val displayArea = android.graphics.Point()
        activeDisplay?.getRealSize(displayArea)
        appendLog(
            "system bars statusVisible=" +
                (insets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: false) +
                " statusInsetPx=" +
                (insets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: -1) +
                " navigationVisible=" +
                (insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: false) +
                " navigationInsetPx=" +
                (insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: -1) +
                " palette=${if (darkMode) "dark" else "light"}" +
                " appliedStatusBarColor=#" + Integer.toHexString(statusBarColor) +
                " lightStatusIcons=" +
                WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars +
                " windowViewport=${window.decorView.width}x${window.decorView.height}" +
                " displayArea=${displayArea.x}x${displayArea.y}" +
                " panel=${mode?.physicalWidth ?: -1}x${mode?.physicalHeight ?: -1}",
        )
    }

    /**
     * The screen shown until CarPlay paints something: the wordmark, the current stage as the one
     * piece of large type, and two terminal blocks - what can be done here, and what the host is
     * doing right now. It replaces the old floating settings button, so once a video stream starts
     * there is nothing left on the picture at all.
     */
    private fun buildIdlePanel(stage: TextView): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    menuText("\u276f", 30f, MENU_ACCENT, bold = true),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(16) },
                )
                addView(
                    menuText("NEVOPlay", 30f, MENU_LABEL, bold = true),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
                addView(
                    menuText("head unit", 22f, MENU_FAINT),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(18) },
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        root.addView(
            stage,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(26) },
        )

        val commands = HostBlock(this, "commands").apply {
            // The one action that answers "I took the phone away and brought it back". It is shown
            // only while it can do something - wireless, no live session, display known.
            reconnectCommandRow = idleCommandRow(
                title = "Connect to iPhone",
                hint = "Start a fresh wireless handshake now",
                chip = "Connect",
            ) { connectToIphoneNow() }.apply { visibility = View.GONE }
            addRow(requireNotNull(reconnectCommandRow))
            addRow(
                idleCommandRow(
                    title = "Settings",
                    hint = "Transport, resolution, frame rate, MFi channel",
                ) { openSettingsMenu() },
            )
            addRow(idleLogRow())
        }

        idleTransportValue = menuText("", 21f, HostUi.TEXT)
        idleHotspotValue = menuText("", 21f, HostUi.TEXT)
        idleMfiValue = menuText("", 21f, HostUi.TEXT)
        val status = HostBlock(this, "status").apply {
            addRow(idleStatusRow("transport", requireNotNull(idleTransportValue)))
            addRow(idleStatusRow("hotspot", requireNotNull(idleHotspotValue)))
            addRow(idleStatusRow("mfi target", requireNotNull(idleMfiValue)))
            addRow(idleStatusRow("build", menuText("v${BuildConfig.APP_VERSION}", 21f, HostUi.TEXT)))
        }

        // Two columns whenever the panel is wide enough - every head unit - and one column on a
        // phone, where two blocks of monospace side by side would leave neither readable.
        val wide = resources.displayMetrics.widthPixels >= IDLE_PANEL_SIDE_BY_SIDE_PX
        val blocks = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        if (wide) {
            blocks.addView(
                commands,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.15f),
            )
            blocks.addView(
                status,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(24) },
            )
        } else {
            blocks.addView(commands, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            blocks.addView(
                status,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) },
            )
        }
        root.addView(
            blocks,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(40) },
        )

        root.applyHostScale(homeTextScale(), homeSpaceScale())
        // The single piece of display type follows the panel, not the font scale: at 64sp a phone
        // held in landscape would give a quarter of its height to one line. The cap is applied
        // against the window as it is *now* and re-applied whenever that window changes - the
        // vehicle showing its status bar turns 2560x1600 into 2560x1440 in the middle of a session.
        idleTitleBasePx = stage.textSize
        updateIdleTitleSize()
        updateIdlePanel()
        return root
    }

    /**
     * One action on the idle screen: prompt, name, hint, and a chip that the row itself handles.
     * The chip label names the verb, so a row that starts a handshake does not read as "Open".
     */
    private fun idleCommandRow(
        title: String,
        hint: String,
        chip: String = "Open",
        onClick: () -> Unit,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(
                LinearLayout(this@CarPlayHostActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        menuText("\u276f", 24f, MENU_ACCENT, bold = true),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(14) },
                    )
                    addView(
                        menuText(title, 26f, MENU_LABEL),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        HostUi.chip(this@CarPlayHostActivity, chip).apply {
                            isClickable = false
                            isFocusable = false
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    )
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            addView(
                menuText(hint, 18f, MENU_SECONDARY).apply { setPadding(dp(38), dp(8), 0, 0) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

    /** The on-screen log switch, same setting as the one in Diagnostics. */
    private fun idleLogRow(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                menuText("On-screen log", 26f, MENU_LABEL),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val toggle = HostToggle(this@CarPlayHostActivity).apply {
                isChecked = debugLogsEnabled
                contentDescription = "Show on-screen debug logs"
                setOnCheckedChangeListener { _, checked ->
                    debugLogsEnabled = checked
                    if (!checked) clearScreenLogs()
                    appendLog("Debug logs ${if (checked) "enabled" else "disabled"}")
                    updateDebugOverlays()
                }
            }
            idleLogToggle = toggle
            addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

    private fun idleStatusRow(key: String, value: TextView): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                menuText(key, 20f, MENU_FAINT),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.42f),
            )
            addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.58f))
        }

    /**
     * Sizes the stage line against the window it is actually in, not the one measured at startup.
     * The width term keeps the line from running off the side; the height term keeps a short panel -
     * a head unit showing the vehicle's status bar is 2560x1440, not 2560x1600 - from giving a
     * quarter of itself to one sentence.
     */
    private fun updateIdleTitleSize() {
        val stage = stageStatusView ?: return
        if (idleTitleBasePx <= 0f) return
        val width = (contentRoot?.width ?: 0).takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val height = (contentRoot?.height ?: 0).takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        stage.setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            minOf(
                idleTitleBasePx,
                height * IDLE_TITLE_HEIGHT_FRACTION,
                width * IDLE_TITLE_WIDTH_FRACTION,
            ),
        )
    }

    /** True while the window covers the system bars and is therefore responsible for their space. */
    private fun windowOwnsSystemBars(): Boolean =
        hideTopBar && hideBottomBar && !vehicleEnforcesSystemBars

    /**
     * Only while the window is edge-to-edge does a visible vehicle status bar lie on top of our
     * content; with decor-fits-system-windows the system has already inset the window, and adding
     * the inset again would push everything down by twice the bar.
     */
    private fun applyBarInsets() {
        val overlap = if (windowOwnsSystemBars()) statusBarTopInsetPx else 0
        idlePanel?.let { panel ->
            if (panel.paddingTop != overlap) panel.setPadding(0, overlap, 0, 0)
        }
        // The header owns the top edge of the panel, so it takes the vehicle's status bar inset;
        // the scrolling column sits below it and needs nothing.
        val header = settingsHeaderView
        val headerBase = settingsHeaderPadding
        if (header != null && headerBase != null) {
            val headerTop = headerBase[1] + overlap
            if (header.paddingTop != headerTop) {
                header.setPadding(headerBase[0], headerTop, headerBase[2], headerBase[3])
            }
        }
        val content = settingsContentView ?: return
        val base = settingsContentPadding ?: return
        val top = base[1]
        if (
            content.paddingTop != top ||
            content.paddingLeft != base[0] ||
            content.paddingRight != base[2] ||
            content.paddingBottom != base[3]
        ) {
            content.setPadding(base[0], top, base[2], base[3])
        }
    }

    private fun updateIdlePanel() {
        idleTransportValue?.text = if (wirelessEnabled) "wireless" else "usb"
        idleHotspotValue?.text = if (wirelessEnabled) hotspotStatus.state else "off"
        idleMfiValue?.text = mfiTargetLabel(mfiTarget).lowercase()
        idleLogToggle?.setCheckedQuietly(debugLogsEnabled)
        // Offered while a fresh handshake can still change something: wireless, no live session,
        // and a display already known. Greyed while a rebuild is running, so a tap that could not
        // do anything is visibly unavailable instead of silently swallowed.
        val offered = wirelessEnabled && activeAirPlaySession == null && activeDisplaySize != null
        reconnectCommandRow?.let { row ->
            row.visibility = if (offered) View.VISIBLE else View.GONE
            val ready = offered && !stackRebuildInProgress
            row.isEnabled = ready
            row.alpha = if (ready) 1f else 0.45f
        }
    }

    private fun buildSettingsMenu(): View {
        val column = settingsPanelOnTheRight()
        val panelWidth = settingsPanelWidth(resources.displayMetrics.widthPixels)
        val side = settingsPanelEdgePadding(panelWidth)
        val overlay = FrameLayout(this).apply {
            // The picture keeps decoding behind this overlay. A full-screen backdrop would hide it,
            // so the surface belongs to the panel and the rest of the screen stays see-through.
            setBackgroundColor(if (column) Color.TRANSPARENT else MENU_BACKGROUND)
            isClickable = true
            // A tap on the picture beside the panel leaves the menu, the way the back control does.
            // The picture is visible only because the session is still running, so a tap there reads
            // as "I meant to touch CarPlay", not as "keep the settings open".
            setOnClickListener { dismissSettingsMenu() }
        }
        // Header above, scrolling column below: the back control stays where the hand expects it
        // instead of scrolling away with the first block.
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MENU_BACKGROUND)
            // Clickable so a tap on the panel's own surface stops at the panel and does not reach the
            // overlay's dismiss handler; its rows and footer handle their own touches.
            isClickable = true
        }
        // One child list, two jobs: category headers stay in the column, while every row added after
        // one lands inside that category's block. Rows therefore keep no margins of their own - the
        // block separates them with a hairline instead.
        val content = object : LinearLayout(this) {
            override fun addView(child: View?, index: Int, params: ViewGroup.LayoutParams?) {
                val view = child ?: return
                val block = settingsBlock
                if (block != null && view !is HostBlock) {
                    block.addRow(view)
                } else {
                    super.addView(view, index, params)
                }
            }

            override fun removeAllViews() {
                settingsBlock = null
                super.removeAllViews()
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(side, (side * 0.75f).toInt(), side, side)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(side, (side * 0.75f).toInt(), side, (side * 0.35f).toInt())
            addView(
                HostUi.chip(this@CarPlayHostActivity, "❯").apply {
                    contentDescription = "Back to the host screen without saving"
                    // The padding shrinks with the panel; the tap target may not.
                    minWidth = HostUi.dp(this@CarPlayHostActivity, 56)
                    setOnClickListener { cancelSettingsEdits() }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(20) },
            )
            addView(
                menuText("Settings", 32f, MENU_LABEL, bold = true),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            // The one line that shows the picture behind the panel is still a running session.
            val live = menuText("", 18f, MENU_SECONDARY).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            settingsLiveView = live
            addView(
                live,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    gravity = Gravity.END
                    marginStart = dp(16)
                },
            )
        }
        settingsHeaderView = header
        content.addView(
            settingsCategoryHeader("Connection"),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(32) },
        )

        content.addView(
            buildMfiTargetSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )

        val wirelessRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        wirelessRow.addView(
            menuText("Wireless CarPlay", 20f, MENU_LABEL),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val wirelessSwitch = HostToggle(this).apply {
            isChecked = wirelessEnabled
            contentDescription = "Wireless CarPlay transport"
            setOnCheckedChangeListener { _, checked ->
                if (wirelessEnabled == checked) return@setOnCheckedChangeListener
                wirelessEnabled = checked
                hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
                updateHotspotStatusBlock()
                appendLog(
                    "Wireless CarPlay ${if (wirelessEnabled) "enabled" else "disabled"}; " +
                        "applies when settings close",
                )
                requestStartupPrerequisites()
            }
        }
        wirelessRow.addView(
            wirelessSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            wirelessRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            buildHotspotModeSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            menuText("Hotspot status", 20f, MENU_LABEL),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(18) },
        )
        val hotspotStatusView = menuText("", 16f, MENU_ACCENT)
        content.addView(
            hotspotStatusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        content.addView(
            settingsCategoryHeader("Diagnostics"),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(40) },
        )
        content.addView(
            buildMediaMetricsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(
            buildDebugLogsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(
            buildAudioPacketCaptureSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(
            menuText(
                "build ${BuildConfig.BUILD_ID}\nlog $sessionLogDestination",
                15f,
                MENU_SECONDARY,
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            content.addView(
                settingsCategoryHeader("Android 9 compatibility"),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(40) },
            )
            content.addView(
                menuText(
                    "Android 9: no Wi-Fi P2P (LocalOnlyHotspot instead), " +
                        "no HEVC software decoder.",
                    16f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) },
            )
        }

        val preview = menuText("", 17f, MENU_SECONDARY)
        content.addView(
            preview,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            settingsCategoryHeader("Location"),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            buildLocationReportingSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content.addView(
            settingsCategoryHeader("Startup"),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            settingsSwitchRow(
                label = "Auto-start on boot",
                checked = autoStartOnBoot,
                description = "Start CarPlay automatically after device boot",
            ) { checked ->
                autoStartOnBoot = checked
                appendLog("Boot auto-start ${if (checked) "enabled" else "disabled"}")
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        content.addView(
            settingsCategoryHeader("Audio"),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            buildMicrophoneGainSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(
            buildMainMediaAudioBufferSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) },
        )
        if (advancedAudioChannelMappingSupported) {
            content.addView(
                settingsSwitchRow(
                    label = "Advanced audio channel mapping",
                    checked = advancedAudioChannelMapping,
                    description = "Route AAOS audio buses by CarPlay audio type",
                ) { checked ->
                    advancedAudioChannelMapping = checked
                    appendLog(
                        "Advanced audio channel mapping ${if (checked) "enabled" else "disabled"}; " +
                            "applies when settings close",
                    )
                    updateResolutionMenu()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(20) },
            )
        }

        content.addView(
            settingsCategoryHeader("Identity & appearance"),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(36) },
        )
        content.addView(
            buildIdentitySettingsSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(
            buildAirPlayIconSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(26) },
        )
        content.addView(
            buildDrivingSideSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(26) },
        )
        content.addView(
            settingsCategoryHeader("Display & video"),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(40) },
        )

        val resolutionHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        resolutionHeader.addView(
            menuText("Resolution", 20f, MENU_LABEL),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val resolutionValue = menuText(
            CarPlayDisplayScale.label(displayScaleTenths),
            28f,
            MENU_ACCENT,
            bold = true,
        )
        resolutionHeader.addView(
            resolutionValue,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            resolutionHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(14) },
        )

        val seekBar = HostSlider(this).apply {
            max = CarPlayDisplayScale.MAX_TENTHS - CarPlayDisplayScale.MIN_TENTHS
            progress = displayScaleTenths - CarPlayDisplayScale.MIN_TENTHS
            setOnChangeListener(
                object : HostSlider.OnChangeListener {
                    override fun onProgressChanged(slider: HostSlider, progress: Int, fromUser: Boolean) {
                        displayScaleTenths = CarPlayDisplayScale.sanitize(
                            CarPlayDisplayScale.MIN_TENTHS + progress,
                        )
                        updateResolutionMenu()
                    }

                    override fun onStartTrackingTouch(slider: HostSlider) = Unit
                    override fun onStopTrackingTouch(slider: HostSlider) = Unit
                },
            )
        }
        content.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        val range = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        range.addView(
            menuText("0.3x", 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        range.addView(
            menuText("1.0x", 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            range,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        content.addView(
            buildStepSliderSection(
                title = "Frame rate",
                values = (
                    AirPlayDisplaySettings.MIN_FPS..AirPlayDisplaySettings.MAX_FPS
                    step AirPlayDisplaySettings.FPS_STEP
                    ).toList(),
                selectedValue = fps,
                label = { "$it fps" },
                onValueChanged = { value ->
                    fps = value
                    updateResolutionMenu()
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )

        content.addView(
            settingsChoiceRow(
                label = "Physical size basis",
                options = listOf(
                    AirPlayPhysicalSizeBasis.WIDTH to "Widest width",
                    AirPlayPhysicalSizeBasis.HEIGHT to "Longest height",
                ),
                selected = physicalSizeBasis,
            ) { value ->
                physicalSizeBasis = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )

        content.addView(
            buildStepSliderSection(
                title = "Physical length",
                values = (
                    AirPlayDisplaySettings.MIN_WIDTH_PHYSICAL_MM..
                        AirPlayDisplaySettings.MAX_WIDTH_PHYSICAL_MM
                    step AirPlayDisplaySettings.WIDTH_PHYSICAL_MM_STEP
                    ).toList(),
                selectedValue = widthPhysicalMm,
                label = { "$it mm" },
                onValueChanged = { value ->
                    widthPhysicalMm = value
                    updateResolutionMenu()
                },
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16) },
        )

        val hevcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hevcRow.addView(
            menuText("HEVC (H.265)", 20f, MENU_LABEL),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val hevcSwitch = HostToggle(this).apply {
            isChecked = hevcEnabled
            contentDescription = "HEVC H.265 video transport"
            setOnCheckedChangeListener { _, checked ->
                if (hevcEnabled == checked) return@setOnCheckedChangeListener
                hevcEnabled = checked
                appendLog(
                    "HEVC (H.265) ${if (hevcEnabled) "enabled" else "disabled"}; " +
                        "applies when settings close",
                )
                updateResolutionMenu()
            }
        }
        hevcRow.addView(
            hevcSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            hevcRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        val softwareHevcRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        softwareHevcRow.addView(
            menuText("HEVC software decoder", 20f, MENU_LABEL),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val softwareHevcSwitch = HostToggle(this).apply {
            isChecked = hevcSoftwareDecoderEnabled
            contentDescription = "Use software HEVC decoder"
            setOnCheckedChangeListener { _, checked ->
                if (hevcSoftwareDecoderEnabled == checked) return@setOnCheckedChangeListener
                hevcSoftwareDecoderEnabled = checked
                appendLog(
                    "HEVC software decoder ${if (hevcSoftwareDecoderEnabled) "enabled" else "disabled"}; " +
                        "applies when settings close",
                )
                updateResolutionMenu()
            }
        }
        softwareHevcRow.addView(
            softwareHevcSwitch,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            content.addView(
                softwareHevcRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(16) },
            )
        }

        content.addView(
            buildSafeAreaSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(30) },
        )

        content.addView(
            settingsCategoryHeader("Window"),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(40) },
        )
        content.addView(
            buildFullscreenSection(),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        content.addView(
            settingsSwitchRow(
                label = "More gestures to Settings page",
                checked = moreGesturesToSettings,
                description = "Enable a one-finger swipe down along the left edge to open settings",
            ) { checked -> moreGesturesToSettings = checked },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )

        // The footer is pinned to the panel instead of living in the scrolling column: "Save and
        // reconnect" must not be something the user has to scroll to find.
        val save = HostUi.chip(this, "Save and reconnect", HostUi.ChipStyle.PRIMARY).apply {
            setOnClickListener { saveSettingsAndReconnect() }
        }
        val exitApplicationButton = HostUi.chip(this, "Exit application", HostUi.ChipStyle.DANGER).apply {
            setOnClickListener { exitApplication() }
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(MENU_BACKGROUND)
            setPadding(side, dp(18), side, dp(18))
            addView(
                menuText("3-finger double tap or Back = return", 17f, MENU_FAINT).apply {
                    maxLines = 2
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                exitApplicationButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(16) },
            )
            addView(
                save,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(12) },
            )
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        panel.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        panel.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        panel.addView(
            footer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(
                panelWidth,
                FrameLayout.LayoutParams.MATCH_PARENT,
                if (column) Gravity.END else Gravity.CENTER,
            ),
        )
        // A hairline where the panel meets the picture: in dark mode both are nearly the same
        // near-black, and without it the two run together.
        val divider = if (column) {
            View(this).apply { setBackgroundColor(HostUi.LINE) }.also { view ->
                overlay.addView(
                    view,
                    FrameLayout.LayoutParams(
                        dp(1),
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.END,
                    ).apply { rightMargin = panelWidth },
                )
            }
        } else {
            null
        }
        overlay.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val desiredWidth = settingsPanelWidth(view.width)
            val params = panel.layoutParams
            if (params.width != desiredWidth) {
                params.width = desiredWidth
                panel.layoutParams = params
            }
            val dividerParams = divider?.layoutParams as? FrameLayout.LayoutParams
            if (dividerParams != null && dividerParams.rightMargin != desiredWidth) {
                dividerParams.rightMargin = desiredWidth
                divider.layoutParams = dividerParams
            }
        }

        overlay.applyHostScale(menuTextScale(), menuSpaceScale())
        settingsContentPadding = intArrayOf(
            content.paddingLeft,
            content.paddingTop,
            content.paddingRight,
            content.paddingBottom,
        )
        settingsContentView = content
        settingsHeaderView?.let { header ->
            settingsHeaderPadding = intArrayOf(
                header.paddingLeft,
                header.paddingTop,
                header.paddingRight,
                header.paddingBottom,
            )
        }

        resolutionValueView = resolutionValue
        resolutionPreviewView = preview
        this.hotspotStatusView = hotspotStatusView
        updateHotspotStatusBlock()
        updateResolutionMenu()
        return overlay
    }

    /**
     * The settings are a right-hand column over the running picture on a head unit: the picture
     * stays visible to the left of it, and opening the menu never stops it. A phone, or a head unit
     * in portrait, has no room for that column, so there the panel keeps the centred full-height
     * layout and its opaque backdrop.
     */
    private fun settingsPanelOnTheRight(): Boolean {
        val metrics = resources.displayMetrics
        return metrics.widthPixels >= RIGHT_COLUMN_SETTINGS_MIN_WIDTH_PX &&
            metrics.widthPixels > metrics.heightPixels
    }

    private fun settingsPanelWidth(availableWidth: Int): Int =
        if (settingsPanelOnTheRight()) {
            minOf(
                (availableWidth * SETTINGS_PANEL_WIDTH_FRACTION).toInt(),
                MAX_SETTINGS_PANEL_WIDTH_PX,
            )
        } else {
            minOf(availableWidth, MAX_SETTINGS_MENU_WIDTH_PX)
        }

    /** Side padding inside the panel: a fraction of the panel, floored and capped so it stays sane. */
    private fun settingsPanelEdgePadding(panelWidth: Int): Int =
        (panelWidth * SETTINGS_PANEL_EDGE_FRACTION).toInt()
            .coerceIn(dp(16), dp(MAX_SETTINGS_PANEL_EDGE_DP))

    /**
     * One factor for the whole panel, derived from its width in dp rather than in pixels: a head
     * unit reports a wide panel at a low density (2560px at 1.5x is 1706dp, so text grows), while a
     * phone reports almost the same pixel width at 3.5x (754dp, so text stays put). Text, padding,
     * margins and fixed sizes all scale by this one factor, which is what keeps the layout
     * proportional on a resolution it was never authored for.
     */
    /**
     * The page margin: 72dp on a head unit, but only a phone's worth on a 350dp-wide panel, where a
     * fixed 72dp would spend a fifth of the width on nothing.
     */
    private fun panelEdgePadding(): Int {
        val widthDp = panelWidthDp(resources.displayMetrics.widthPixels)
        return (widthDp * PANEL_EDGE_FRACTION).toInt().coerceIn(dp(16), dp(72))
    }

    private fun panelWidthDp(panelWidthPx: Int): Float =
        panelWidthPx / resources.displayMetrics.density

    private fun hostTextScale(panelWidthPx: Int): Float =
        (panelWidthDp(panelWidthPx) / HostUi.REFERENCE_WIDTH_DP)
            .coerceIn(HostUi.MIN_TEXT_SCALE, HostUi.MAX_TEXT_SCALE)

    private fun hostSpaceScale(panelWidthPx: Int): Float =
        (panelWidthDp(panelWidthPx) / HostUi.REFERENCE_WIDTH_DP)
            .coerceIn(HostUi.MIN_SPACE_SCALE, HostUi.MAX_SPACE_SCALE)

    /** The settings column is capped, so its own width is what the panel scales against. */
    private fun menuTextScale(): Float =
        hostTextScale(settingsPanelWidth(resources.displayMetrics.widthPixels))

    private fun menuSpaceScale(): Float =
        hostSpaceScale(settingsPanelWidth(resources.displayMetrics.widthPixels))

    private fun homeTextScale(): Float =
        hostTextScale(resources.displayMetrics.widthPixels)

    private fun homeSpaceScale(): Float =
        hostSpaceScale(resources.displayMetrics.widthPixels)

    private fun persistMenuSettings() {
        AirPlayPersistence.saveWirelessEnabled(this, wirelessEnabled)
        AirPlayPersistence.saveMfiTarget(this, mfiTarget)
        AirPlayPersistence.saveMfiI2cPath(this, mfiI2cPath)
        AirPlayPersistence.saveRemoteMfiServer(this, remoteMfiServer)
        AirPlayPersistence.saveRemoteMfiToken(this, remoteMfiToken)
        AirPlayPersistence.saveLocalMfiCertificateUri(this, localMfiCertificateUri)
        AirPlayPersistence.saveLocalMfiPrivateKeyUri(this, localMfiPrivateKeyUri)
        AirPlayPersistence.saveWirelessHotspotMode(this, wirelessHotspotMode)
        AirPlayPersistence.saveManualHotspotSsid(this, manualHotspotSsid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, manualHotspotPassphrase)
        AirPlayPersistence.saveManualHotspotBand(this, manualHotspotBand)
        AirPlayPersistence.saveManualHotspotChannel(this, manualHotspotChannel)
        AirPlayPersistence.saveManualHotspotSecurity(this, manualHotspotSecurity)
        AirPlayPersistence.saveLocationReportingEnabled(this, locationReportingEnabled)
        AirPlayPersistence.saveAutoStartOnBoot(this, autoStartOnBoot)
        AirPlayPersistence.saveAdvancedAudioChannelMapping(this, advancedAudioChannelMapping)
        AirPlayPersistence.saveMicrophoneGainPercent(this, microphoneGainPercent)
        AirPlayPersistence.saveMainMediaAudioBufferDurationMs(this, mainMediaAudioBufferDurationMs)
        AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
        AirPlayPersistence.saveFps(this, fps)
        AirPlayPersistence.saveWidthPhysicalMm(this, widthPhysicalMm)
        AirPlayPersistence.savePhysicalSizeBasis(this, physicalSizeBasis)
        AirPlayPersistence.saveHevcEnabled(this, hevcEnabled)
        AirPlayPersistence.saveHevcSoftwareDecoderEnabled(this, hevcSoftwareDecoderEnabled)
        AirPlayPersistence.saveManufacturer(this, manufacturer)
        AirPlayPersistence.saveModel(this, model)
        AirPlayPersistence.saveOemLabel(this, oemLabel)
        AirPlayPersistence.saveDebugLogsEnabled(this, debugLogsEnabled)
        AirPlayPersistence.saveAudioPacketCaptureEnabled(this, audioPacketCaptureEnabled)
        AirPlayPersistence.saveMediaMetricsEnabled(this, mediaMetricsEnabled)
        AirPlayPersistence.saveMoreGesturesToSettings(this, moreGesturesToSettings)
        AirPlayPersistence.saveRightHandDrive(this, rightHandDrive)
        AirPlayPersistence.saveHideTopBar(this, hideTopBar)
        AirPlayPersistence.saveHideBottomBar(this, hideBottomBar)
        AirPlayPersistence.saveSafeAreaDrawOutside(this, safeAreaDrawOutside)
    }

    private fun captureSettingsBaseline(): SettingsBaseline {
        val safeAreaSize = currentActivitySize()
        val customIconBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (error: Exception) {
            Log.w(TAG, "Could not read the current AirPlay icon for settings rollback", error)
            null
        }
        return SettingsBaseline(
            safeAreaSize = safeAreaSize,
            safeAreaRect = safeAreaSize?.let {
                AirPlayPersistence.loadSafeAreaRect(this, it.width, it.height)
            },
            customIconBytes = customIconBytes,
        )
    }

    private fun restoreSettingsBaseline() {
        val baseline = settingsBaseline ?: return
        loadPersistedSettings()
        baseline.safeAreaSize?.let { size ->
            baseline.safeAreaRect?.let { rect ->
                AirPlayPersistence.saveSafeAreaRect(
                    this,
                    size.width,
                    size.height,
                    rect,
                    commit = true,
                )
            } ?: AirPlayPersistence.clearSafeAreaRect(
                this,
                size.width,
                size.height,
                commit = true,
            )
        }
        try {
            baseline.customIconBytes?.let { bytes ->
                AirPlayPersistence.saveCustomAirPlayIcon(this, bytes)
            } ?: AirPlayPersistence.clearCustomAirPlayIcon(this)
        } catch (error: Exception) {
            Log.w(TAG, "Could not restore the previous AirPlay icon", error)
        }
        settingsBaseline = null
        locationPermissionAvailable = hasFineLocationPermission()
        // The hotspot is not part of the settings any more: the session keeps running behind the
        // menu, so its state is whatever the controller last reported, not "stopped".
        syncMfiSettingsControls()
        syncMicrophoneGainControls()
        syncMainMediaAudioBufferControls()
        updateManualHotspotFields()
        updateAirPlayIconPreview()
        updateSafeAreaSummary()
        updateHotspotStatusBlock()
        updateResolutionMenu()
        updateDebugOverlays()
        resetVehicleSystemBarLatch()
        applyFullscreenMode()
        refreshDisplaySizeAfterLayout()
    }

    private fun buildMfiTargetSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val targetChoice = settingsChoiceRow(
            label = "MFI certificate & signing target",
            options = listOf(
                MfiTarget.USB_CH341 to "USB/CH341",
                MfiTarget.I2C to "I2C",
                MfiTarget.REMOTE to "Remote",
                MfiTarget.LOCAL_FILES to "Local files",
            ),
            selected = mfiTarget,
        ) { target ->
            if (mfiTarget == target) return@settingsChoiceRow
            mfiTarget = target
            updateMfiTargetFields()
            appendLog("MFI target: ${mfiTargetLabel(target)}; applies when settings close")
        }
        mfiTargetGroup = (targetChoice as ViewGroup).getChildAt(1) as RadioGroup
        section.addView(
            targetChoice,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val i2cFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    "I2C device",
                    mfiI2cPath,
                    onInputCreated = { mfiI2cPathInput = it },
                ) { value ->
                    mfiI2cPath = value
                    mfiErrorView?.visibility = View.GONE
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText("/dev/i2c-1", 14f, MENU_FAINT),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            i2cFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiI2cFields = i2cFields

        val remoteFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                settingsInputRow(
                    "Server address",
                    remoteMfiServer,
                    onInputCreated = { remoteMfiServerInput = it },
                ) { value ->
                    remoteMfiServer = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                settingsInputRow(
                    "Token (optional)",
                    remoteMfiToken,
                    password = true,
                    onInputCreated = { remoteMfiTokenInput = it },
                ) { value ->
                    remoteMfiToken = value
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
            addView(
                menuText("http(s):// · token optional (bearer)", 14f, MENU_FAINT),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        section.addView(
            remoteFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiRemoteFields = remoteFields

        // Create the app-owned directory while the user is looking at the target, so an
        // `adb push .../files/mfi/` has somewhere to land before the first connection.
        LocalMfiDocuments.appDirectory(this)?.directory?.mkdirs()

        val localFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                localMfiDocumentRow(
                    "Certificate (.p7b)",
                    localMfiCertificateUri,
                    onDocumentViewCreated = { localMfiCertificateDocumentView = it },
                ) {
                    launchLocalMfiPicker(localMfiCertificatePicker, "certificate")
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                localMfiDocumentRow(
                    "Private key (.pk8)",
                    localMfiPrivateKeyUri,
                    onDocumentViewCreated = { localMfiPrivateKeyDocumentView = it },
                ) {
                    launchLocalMfiPicker(localMfiPrivateKeyPicker, "private key")
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
            addView(
                menuText(
                    "Select a DER PKCS#7 certificate and its matching unencrypted DER PKCS#8 " +
                        "private key. Documents are reloaded when MFI reconnects.",
                    14f,
                    MENU_SECONDARY,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
            // A head unit whose system picker cannot hand out a certificate uses one of the fixed
            // directories instead, so the panel has to name the files it would actually read.
            val status = menuText("", 14f, MENU_FAINT)
            localMfiStatusView = status
            addView(
                status,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
            addView(
                HostUi.chip(this@CarPlayHostActivity, "Refresh files").apply {
                    contentDescription = "Look for the fixed MFi files again"
                    setOnClickListener { refreshLocalMfiSources() }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
        }
        section.addView(
            localFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        mfiLocalFields = localFields
        val error = menuText("", 14f, MENU_DANGER).apply {
            visibility = View.GONE
        }
        section.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        mfiErrorView = error
        updateLocalMfiStatus()
        updateMfiTargetFields()
        return section
    }

    private fun updateMfiTargetFields() {
        mfiI2cFields?.visibility = if (mfiTarget == MfiTarget.I2C) View.VISIBLE else View.GONE
        mfiRemoteFields?.visibility = if (mfiTarget == MfiTarget.REMOTE) View.VISIBLE else View.GONE
        mfiLocalFields?.visibility = if (mfiTarget == MfiTarget.LOCAL_FILES) View.VISIBLE else View.GONE
        mfiErrorView?.visibility = View.GONE
    }

    private fun localMfiDocumentRow(
        label: String,
        uri: String,
        onDocumentViewCreated: (TextView) -> Unit,
        onChoose: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            menuText(label, 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val row = LinearLayout(this@CarPlayHostActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val documentView = menuText(localMfiDocumentLabel(uri), 14f, MENU_SECONDARY)
        onDocumentViewCreated(documentView)
        row.addView(
            documentView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        row.addView(
            HostUi.chip(this@CarPlayHostActivity, "Choose").apply {
                contentDescription = "Choose $label"
                setOnClickListener { onChoose() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(12) },
        )
        addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) },
        )
    }

    private fun updateLocalMfiDocumentViews() {
        localMfiCertificateDocumentView?.text = localMfiDocumentLabel(localMfiCertificateUri)
        localMfiPrivateKeyDocumentView?.text = localMfiDocumentLabel(localMfiPrivateKeyUri)
    }

    /**
     * Opens the system picker, or explains that this head unit does not have one.
     *
     * A panel without a document provider throws [ActivityNotFoundException] from the launch, which
     * is precisely the case the fixed directories cover — so the failure is reported and the status
     * line below the buttons then names the files that will be read instead.
     */
    private fun launchLocalMfiPicker(
        picker: ActivityResultLauncher<Array<String>>,
        label: String,
    ) {
        externalActivityInProgress = true
        try {
            picker.launch(arrayOf("*/*"))
        } catch (error: ActivityNotFoundException) {
            externalActivityInProgress = false
            appendLog("The head unit has no file picker for the local MFi $label: ${error.message}")
            Toast.makeText(
                this,
                "No file picker on this head unit — using the fixed MFi paths",
                Toast.LENGTH_LONG,
            ).show()
            refreshLocalMfiSources()
        }
    }

    private fun refreshLocalMfiSources() {
        updateLocalMfiDocumentViews()
        updateLocalMfiStatus()
        mfiErrorView?.visibility = View.GONE
    }

    /**
     * Names the material the next MFI connection will use: the chosen documents, or the first fixed
     * directory that holds a readable pair. Saying which copy won matters here, because the fixed
     * directories can exist and still be unreadable on newer Android versions.
     */
    private fun updateLocalMfiStatus() {
        val view = localMfiStatusView ?: return
        val documentsChosen =
            localMfiCertificateUri.isNotBlank() && localMfiPrivateKeyUri.isNotBlank()
        if (documentsChosen) {
            view.text = "Source: the two chosen documents"
            view.setTextColor(MENU_SECONDARY)
            return
        }
        val partialDocuments =
            localMfiCertificateUri.isNotBlank() || localMfiPrivateKeyUri.isNotBlank()
        val sources = LocalMfiDocuments.sources(this)
        val usable = sources.firstOrNull {
            LocalMfiDocuments.state(it) == LocalMfiDocuments.SourceState.USABLE
        }
        if (usable != null) {
            view.text = "Source: ${usable.displayPath}"
            view.setTextColor(MENU_SECONDARY)
            return
        }
        val partial = sources.firstOrNull {
            LocalMfiDocuments.state(it) == LocalMfiDocuments.SourceState.PARTIAL
        }
        if (partial is LocalMfiDocuments.DirectorySource) {
            val names = partial.unreadableFileNames().joinToString(", ")
            view.text = "Found ${partial.displayPath}, but $names cannot be read on this Android " +
                "version. Use a build with the certificate built in, or push the pair to " +
                appFilesMfiPath()
            view.setTextColor(MENU_DANGER)
            return
        }
        view.text = if (partialDocuments) {
            "Choose both documents, or place ${LocalMfiDocuments.CERTIFICATE_FILE_NAME} + " +
                "${LocalMfiDocuments.PRIVATE_KEY_FILE_NAME} in one of the fixed directories"
        } else {
            "No certificate material yet. Choose both documents, or push " +
                "${LocalMfiDocuments.CERTIFICATE_FILE_NAME} + " +
                "${LocalMfiDocuments.PRIVATE_KEY_FILE_NAME} to " +
                LocalMfiDocuments.directories(this).joinToString(" or ") { it.displayPath }
        }
        view.setTextColor(MENU_DANGER)
    }

    private fun appFilesMfiPath(): String =
        LocalMfiDocuments.appDirectory(this)?.displayPath ?: "the app files directory"

    private fun localMfiDocumentLabel(value: String): String {
        if (value.isEmpty()) return "Not selected"
        val uri = try {
            Uri.parse(value)
        } catch (_: Exception) {
            return "Selection unavailable"
        }
        return try {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) cursor.getString(column) else null
            } ?: uri.lastPathSegment ?: "Selected document"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "Selected document"
        }
    }

    private fun retainDocumentReadPermission(uri: Uri, label: String): Boolean = try {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (failure: SecurityException) {
        Log.e(TAG, "Could not retain local MFi $label document permission", failure)
        Toast.makeText(
            this,
            "Could not keep access to the selected $label",
            Toast.LENGTH_LONG,
        ).show()
        false
    }

    private fun syncMfiSettingsControls() {
        mfiTargetGroup?.let { group ->
            val button = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filterIsInstance<RadioButton>()
                .firstOrNull { it.tag == mfiTarget }
            button?.let { group.check(it.id) }
        }
        if (mfiI2cPathInput?.text?.toString() != mfiI2cPath) {
            mfiI2cPathInput?.setText(mfiI2cPath)
        }
        if (remoteMfiServerInput?.text?.toString() != remoteMfiServer) {
            remoteMfiServerInput?.setText(remoteMfiServer)
        }
        if (remoteMfiTokenInput?.text?.toString() != remoteMfiToken) {
            remoteMfiTokenInput?.setText(remoteMfiToken)
        }
        updateLocalMfiDocumentViews()
        updateLocalMfiStatus()
        updateMfiTargetFields()
    }

    private fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.USB_CH341 -> "USB/CH341"
        MfiTarget.I2C -> "I2C"
        MfiTarget.REMOTE -> "Remote"
        MfiTarget.LOCAL_FILES -> "Local files"
    }

    private fun buildIdentitySettingsSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            settingsInputRow("Manufacturer", manufacturer) { value ->
                manufacturer = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            settingsInputRow("Model", model) { value ->
                model = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsInputRow("OEM label", oemLabel) { value ->
                oemLabel = value
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        return section
    }

    /**
     * Opens a category block. The rows added to the settings column after this call land inside it
     * until the next category opens; see the redirecting container in [buildSettingsMenu].
     */
    private fun settingsCategoryHeader(title: String): HostBlock =
        HostBlock(this, title).apply {
            settingsBlock = this
        }

    /**
     * A radio button as a terminal chip: no circle, a bordered box that fills in when selected.
     * Keeping RadioButton and RadioGroup means every existing check-and-sync path keeps working.
     */
    private fun styleChoiceButton(button: RadioButton) {
        button.typeface = HostUi.mono()
        button.textSize = 19f
        button.isAllCaps = false
        button.gravity = Gravity.CENTER
        button.includeFontPadding = false
        button.setButtonDrawable(null as Drawable?)
        button.buttonTintList = null
        button.setPadding(dp(20), dp(14), dp(20), dp(14))
        button.setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(HostUi.ACCENT, HostUi.DIM),
            ),
        )
        button.background = StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_checked),
                HostUi.rounded(this@CarPlayHostActivity, 8, HostUi.ACCENT_WASH, HostUi.ACCENT_DIM),
            )
            addState(
                intArrayOf(),
                HostUi.rounded(this@CarPlayHostActivity, 8, HostUi.SURFACE_2, HostUi.LINE_2),
            )
        }
    }

    private fun buildLocationReportingSection(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val row = LinearLayout(this@CarPlayHostActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(
                menuText("Report location to iPhone", 20f, MENU_LABEL),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            val switch = HostToggle(this@CarPlayHostActivity).apply {
                isChecked = locationReportingEnabled
                contentDescription = "Report Android location to the iPhone"
                setOnCheckedChangeListener { _, checked ->
                    onLocationReportingChanged(checked)
                }
            }
            locationReportingSwitch = switch
            row.addView(
                switch,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                row,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                menuText(
                    "Sent to the iPhone when it asks for a position.",
                    14f,
                    MENU_FAINT,
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(6) },
            )
        }

    private fun onLocationReportingChanged(checked: Boolean) {
        if (locationReportingEnabled == checked) return
        locationReportingEnabled = checked
        appendLog(
            "Location reporting ${if (locationReportingEnabled) "enabled" else "disabled"}; " +
                "applies when settings close",
        )
        updateResolutionMenu()
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
        }
    }

    private fun buildMediaMetricsSection(): View =
        settingsSwitchRow(
            label = "Media latency monitor",
            checked = mediaMetricsEnabled,
            description = "Show live video latency, frame rate, and audio latency",
        ) { checked ->
            mediaMetricsEnabled = checked
            appendLog("Media latency monitor ${if (checked) "enabled" else "disabled"}")
            updateMediaMetricsOverlay()
        }

    private fun buildDebugLogsSection(): View =
        settingsSwitchRow(
            label = "Debug logs",
            checked = debugLogsEnabled,
            description = "Show on-screen debug logs",
        ) { checked ->
            debugLogsEnabled = checked
            if (!checked) clearScreenLogs()
            appendLog("Debug logs ${if (debugLogsEnabled) "enabled" else "disabled"}")
            updateDebugOverlays()
        }

    private fun buildAudioPacketCaptureSection(): View =
        settingsSwitchRow(
            label = "Audio diagnostic capture",
            checked = audioPacketCaptureEnabled,
            description = "Capture encrypted UDP and decrypted RTP audio packets for diagnostics; " +
                "takes effect on the next connection",
        ) { checked ->
            audioPacketCaptureEnabled = checked
            appendLog(
                "Audio diagnostic capture ${if (audioPacketCaptureEnabled) "enabled" else "disabled"}",
            )
        }

    private fun buildMicrophoneGainSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            menuText("Microphone gain (0.8x–2.0x)", 20f, MENU_LABEL),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val gainValue = menuText(
            microphoneGainLabel(microphoneGainPercent),
            22f,
            MENU_ACCENT,
            bold = true,
        )
        microphoneGainValueView = gainValue
        header.addView(
            gainValue,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val gainSlider = HostSlider(this).apply {
            max = (MicrophoneGain.MAX_PERCENT - MicrophoneGain.MIN_PERCENT) /
                MicrophoneGain.STEP_PERCENT
            progress = (microphoneGainPercent - MicrophoneGain.MIN_PERCENT) /
                MicrophoneGain.STEP_PERCENT
            contentDescription = "Microphone gain"
            setOnChangeListener(
                object : HostSlider.OnChangeListener {
                    override fun onProgressChanged(
                        slider: HostSlider,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        val percent = MicrophoneGain.sanitize(
                            MicrophoneGain.MIN_PERCENT +
                                progress * MicrophoneGain.STEP_PERCENT,
                        )
                        microphoneGainPercent = percent
                        microphoneGainValueView?.text = microphoneGainLabel(percent)
                    }

                    override fun onStartTrackingTouch(slider: HostSlider) = Unit
                    override fun onStopTrackingTouch(slider: HostSlider) = Unit
                },
            )
        }
        microphoneGainSeekBar = gainSlider
        controls.addView(
            gainSlider,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val testButton = HostUi.chip(this, "Test", HostUi.ChipStyle.PRIMARY).apply {
            contentDescription = "Test microphone level"
            setOnClickListener {
                if (microphoneLevelMonitor?.isRunning == true) {
                    stopMicrophoneGainTest()
                } else {
                    startMicrophoneGainTest()
                }
            }
        }
        microphoneTestButton = testButton
        controls.addView(
            testButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(12) },
        )
        section.addView(
            controls,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )

        val levelHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        levelHeader.addView(
            menuText("Peak level", 15f, MENU_SECONDARY),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val levelValue = menuText("0%", 15f, MENU_SECONDARY)
        microphoneLevelValueView = levelValue
        levelHeader.addView(
            levelValue,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            levelHeader,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        val levelBar = ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal,
        ).apply {
            max = 100
            progress = 0
            progressBackgroundTintList = ColorStateList.valueOf(MENU_TRACK_OFF)
            contentDescription = "Microphone peak level"
        }
        microphoneLevelBar = levelBar
        section.addView(
            levelBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(14),
            ).apply { topMargin = dp(4) },
        )
        return section
    }

    private fun microphoneGainLabel(percent: Int): String =
        String.format(Locale.US, "%.1fx", MicrophoneGain.sanitize(percent) / 100.0)

    /**
     * Buffered duration of the main media AudioTrack. Larger values ride out bursts at the cost of
     * latency, which is the trade the head unit's audio path tends to force; only music and media
     * streams use it (see [MainMediaAudioBuffer]).
     */
    private fun buildMainMediaAudioBufferSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            menuText(
                "Media audio buffer (${MainMediaAudioBuffer.MIN_DURATION_MS}–" +
                    "${MainMediaAudioBuffer.MAX_DURATION_MS} ms)",
                20f,
                MENU_LABEL,
            ),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val bufferValue = menuText(
            mainMediaAudioBufferLabel(mainMediaAudioBufferDurationMs),
            22f,
            MENU_ACCENT,
            bold = true,
        )
        mainMediaAudioBufferValueView = bufferValue
        header.addView(
            bufferValue,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val bufferSlider = HostSlider(this).apply {
            max = (MainMediaAudioBuffer.MAX_DURATION_MS - MainMediaAudioBuffer.MIN_DURATION_MS) /
                MainMediaAudioBuffer.STEP_DURATION_MS
            progress = mainMediaAudioBufferProgress(mainMediaAudioBufferDurationMs)
            contentDescription = "Media audio buffer duration"
            setOnChangeListener(
                object : HostSlider.OnChangeListener {
                    override fun onProgressChanged(
                        slider: HostSlider,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        val durationMs = MainMediaAudioBuffer.sanitizeDurationMs(
                            MainMediaAudioBuffer.MIN_DURATION_MS +
                                progress * MainMediaAudioBuffer.STEP_DURATION_MS,
                        )
                        mainMediaAudioBufferDurationMs = durationMs
                        mainMediaAudioBufferValueView?.text = mainMediaAudioBufferLabel(durationMs)
                    }

                    override fun onStartTrackingTouch(slider: HostSlider) = Unit
                    override fun onStopTrackingTouch(slider: HostSlider) = Unit
                },
            )
        }
        mainMediaAudioBufferSlider = bufferSlider
        section.addView(
            bufferSlider,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) },
        )
        section.addView(
            menuText(
                "Music and media only. Smaller buffers cut audio latency, larger ones survive " +
                    "bursts; takes effect on the next connection.",
                14f,
                MENU_SECONDARY,
            ),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        return section
    }

    private fun mainMediaAudioBufferLabel(durationMs: Int): String =
        "${MainMediaAudioBuffer.sanitizeDurationMs(durationMs)} ms"

    private fun mainMediaAudioBufferProgress(durationMs: Int): Int =
        (MainMediaAudioBuffer.sanitizeDurationMs(durationMs) - MainMediaAudioBuffer.MIN_DURATION_MS) /
            MainMediaAudioBuffer.STEP_DURATION_MS

    private fun syncMainMediaAudioBufferControls() {
        mainMediaAudioBufferValueView?.text =
            mainMediaAudioBufferLabel(mainMediaAudioBufferDurationMs)
        mainMediaAudioBufferSlider?.progress =
            mainMediaAudioBufferProgress(mainMediaAudioBufferDurationMs)
    }

    private fun syncMicrophoneGainControls() {
        microphoneGainValueView?.text = microphoneGainLabel(microphoneGainPercent)
        microphoneGainSeekBar?.progress =
            (microphoneGainPercent - MicrophoneGain.MIN_PERCENT) /
                MicrophoneGain.STEP_PERCENT
    }

    private fun startMicrophoneGainTest() {
        if (!menuOpen || stackRebuildInProgress || microphoneLevelMonitor != null) return
        if (!microphoneAvailable) {
            microphoneGainTestAfterPermission = true
            microphoneLevelValueView?.text = "Permission required"
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        lateinit var monitor: MicrophoneLevelMonitor
        monitor = MicrophoneLevelMonitor(
            context = applicationContext,
            gainPercent = { microphoneGainPercent },
            onPeakPercent = { peak ->
                runOnUiThread {
                    if (microphoneLevelMonitor !== monitor || !menuOpen || isDestroyed) {
                        return@runOnUiThread
                    }
                    microphoneLevelBar?.progress = peak
                    microphoneLevelValueView?.text = "$peak%"
                }
            },
            onStopped = { error ->
                runOnUiThread {
                    if (microphoneLevelMonitor !== monitor) return@runOnUiThread
                    microphoneLevelMonitor = null
                    microphoneTestButton?.text = "Test"
                    microphoneTestButton?.isEnabled = menuOpen && !stackRebuildInProgress
                    if (error != null) {
                        Log.e(TAG, "microphone gain test failed", error)
                        microphoneLevelBar?.progress = 0
                        microphoneLevelValueView?.text =
                            error.message ?: "Test failed"
                    }
                }
            },
        )
        microphoneLevelMonitor = monitor
        microphoneTestButton?.text = "Stop"
        microphoneLevelBar?.progress = 0
        microphoneLevelValueView?.text = "Listening…"
        if (!monitor.start()) {
            microphoneLevelMonitor = null
            microphoneTestButton?.text = "Test"
        }
    }

    private fun stopMicrophoneGainTest() {
        microphoneGainTestAfterPermission = false
        val monitor = microphoneLevelMonitor
        microphoneLevelMonitor = null
        monitor?.close()
        microphoneTestButton?.text = "Test"
        microphoneLevelBar?.progress = 0
        microphoneLevelValueView?.text = "0%"
    }

    /**
     * The iPhone asks for the car UI when the driver taps the vehicle icon in CarPlay - the plist
     * advertises support through its `enhancedRequestCarUI` extended feature, and the request
     * arrives as a `requestUI` command. Bring the head unit's own launcher forward; the CarPlay
     * session keeps running in the background session, exactly as when the user leaves the activity.
     */
    private fun showHeadUnitHome() {
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(home)
        } catch (error: ActivityNotFoundException) {
            appendLog("The head unit has no home screen to return to: ${error.message}")
            Toast.makeText(this, "No head unit home screen available", Toast.LENGTH_LONG).show()
        } catch (error: SecurityException) {
            appendLog("Cannot open the head unit home screen: ${error.message}")
            Toast.makeText(this, "Cannot open the head unit home screen", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildStepSliderSection(
        title: String,
        values: List<Int>,
        selectedValue: Int,
        label: (Int) -> String,
        onValueChanged: (Int) -> Unit,
    ): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            menuText(title, 20f, MENU_LABEL),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val selectedIndex = values.indexOf(selectedValue)
            .takeIf { it >= 0 }
            ?: 0
        val valueView = menuText(label(values[selectedIndex]), 22f, MENU_ACCENT, bold = true)
        header.addView(
            valueView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val seekBar = HostSlider(this).apply {
            max = (values.size - 1).coerceAtLeast(0)
            progress = selectedIndex
            setOnChangeListener(
                object : HostSlider.OnChangeListener {
                    override fun onProgressChanged(slider: HostSlider, progress: Int, fromUser: Boolean) {
                        val value = values.getOrNull(progress) ?: return
                        valueView.text = label(value)
                        if (fromUser) onValueChanged(value)
                    }

                    override fun onStartTrackingTouch(slider: HostSlider) = Unit
                    override fun onStopTrackingTouch(slider: HostSlider) = Unit
                },
            )
        }
        section.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        return section
    }

    private fun buildAirPlayIconSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText("AirPlay icon", 20f, MENU_LABEL),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(MENU_TRACK_OFF)
            }
        }
        row.addView(
            preview,
            LinearLayout.LayoutParams(dp(72), dp(72)),
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        actions.addView(
            HostUi.chip(this, "Choose image").apply {
                setOnClickListener {
                    externalActivityInProgress = true
                    imagePicker.launch("image/*")
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        actions.addView(
            HostUi.chip(this, "Default icon").apply {
                setOnClickListener {
                    AirPlayPersistence.clearCustomAirPlayIcon(this@CarPlayHostActivity)
                    updateAirPlayIconPreview()
                }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        row.addView(
            actions,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(16) },
        )
        section.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        val status = menuText("", 14f, MENU_SECONDARY)
        section.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        iconPreviewView = preview
        iconStatusView = status
        updateAirPlayIconPreview()
        return section
    }

    private fun buildDrivingSideSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText("Driving side", 20f, MENU_LABEL),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val left = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Left-hand drive"
            styleChoiceButton(this)
            isChecked = !rightHandDrive
        }
        val right = RadioButton(this).apply {
            id = View.generateViewId()
            text = "Right-hand drive"
            styleChoiceButton(this)
            isChecked = rightHandDrive
        }
        group.addView(
            left,
            RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(10) },
        )
        group.addView(
            right,
            RadioGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        group.setOnCheckedChangeListener { _, checkedId ->
            rightHandDrive = checkedId == right.id
            updateResolutionMenu()
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        return section
    }

    private fun buildFullscreenSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText("Fullscreen", 20f, MENU_LABEL),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        section.addView(
            settingsSwitchRow(
                label = "Hide top bar",
                checked = hideTopBar,
                description = "Hide the status bar",
            ) { checked ->
                hideTopBar = checked
                resetVehicleSystemBarLatch()
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsSwitchRow(
                label = "Hide bottom bar",
                checked = hideBottomBar,
                description = "Hide the navigation bar",
            ) { checked ->
                hideBottomBar = checked
                resetVehicleSystemBarLatch()
                applyFullscreenMode()
                refreshDisplaySizeAfterLayout()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        return section
    }

    private fun buildSafeAreaSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText("Safe area", 20f, MENU_LABEL),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val summary = menuText("", 15f, MENU_ACCENT)
        section.addView(
            summary,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) },
        )
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        buttons.addView(
            HostUi.chip(this, "Set").apply {
                setOnClickListener { openSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        buttons.addView(
            HostUi.chip(this, "Reset").apply {
                setOnClickListener { resetSafeAreaForCurrentSize() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        section.addView(
            buttons,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )
        section.addView(
            settingsSwitchRow(
                label = "Draw outside safe area",
                checked = safeAreaDrawOutside,
                description = "Allow CarPlay UI outside the safe area",
            ) { checked ->
                safeAreaDrawOutside = checked
                updateResolutionMenu()
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) },
        )
        safeAreaSummaryView = summary
        updateSafeAreaSummary()
        return section
    }

    private fun buildSafeAreaEditor(): View {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(HostUi.BG)
            isClickable = true
        }
        val editor = SafeAreaEditorView(this)
        overlay.addView(
            editor,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        overlay.addView(
            menuText("\u276f safe area", 26f, MENU_LABEL, bold = true).apply {
                setPadding(dp(20), dp(16), dp(20), dp(8))
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START,
            ),
        )
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(16))
        }
        controls.addView(
            HostUi.chip(this, "Cancel").apply {
                setOnClickListener { closeSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        controls.addView(
            HostUi.chip(this, "Save", HostUi.ChipStyle.PRIMARY).apply {
                setOnClickListener { saveSafeAreaEditor() }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        overlay.addView(
            controls,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
        safeAreaEditorView = editor
        return overlay
    }

    private fun settingsInputRow(
        label: String,
        value: String,
        password: Boolean = false,
        numeric: Boolean = false,
        onInputCreated: ((EditText) -> Unit)? = null,
        onChanged: (String) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            menuText(label, 18f, MENU_LABEL).apply {
                gravity = Gravity.CENTER_VERTICAL
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        addView(
            EditText(this@CarPlayHostActivity).apply {
                setText(value)
                textSize = 19f
                typeface = HostUi.mono()
                setTextColor(HostUi.TEXT)
                setHintTextColor(HostUi.FAINT)
                background = HostUi.rounded(this@CarPlayHostActivity, 8, HostUi.SURFACE_3, HostUi.LINE_2)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                minHeight = dp(56)
                isSingleLine = true
                inputType = when {
                    numeric -> InputType.TYPE_CLASS_NUMBER
                    password -> InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                addTextChangedListener(afterTextChanged(onChanged))
                onInputCreated?.invoke(this)
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { marginStart = dp(12) },
        )
    }

    private fun settingsSwitchRow(
        label: String,
        checked: Boolean,
        description: String,
        onChanged: (Boolean) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            menuText(label, 18f, MENU_LABEL),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            HostToggle(this@CarPlayHostActivity).apply {
                isChecked = checked
                contentDescription = description
                setOnCheckedChangeListener { _, value -> onChanged(value) }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun afterTextChanged(onChanged: (String) -> Unit): TextWatcher =
        object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(text: Editable?) {
                onChanged(text?.toString().orEmpty())
            }
        }

    private fun buildHotspotModeSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        section.addView(
            menuText("Wi-Fi session", 20f, MENU_LABEL),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val group = RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        val modes = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(WirelessHotspotMode.WIFI_P2P to "Wi-Fi P2P (5 GHz)")
            }
            add(WirelessHotspotMode.LOCAL_ONLY_HOTSPOT to "LocalOnlyHotspot")
            add(WirelessHotspotMode.MANUAL to "Manual hotspot")
        }
        var selectedId = View.NO_ID
        for ((mode, label) in modes) {
            val button = RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                styleChoiceButton(this)
                tag = mode
                isChecked = wirelessHotspotMode == mode
            }
            if (wirelessHotspotMode == mode) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) },
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val selected = radioGroup.findViewById<RadioButton>(checkedId)
                ?.tag as? WirelessHotspotMode
                ?: return@setOnCheckedChangeListener
            if (wirelessHotspotMode == selected) return@setOnCheckedChangeListener
            wirelessHotspotMode = selected
            hotspotStatus = HotspotStatus(state = if (wirelessEnabled) "stopped" else "off")
            updateHotspotStatusBlock()
            updateManualHotspotFields()
            appendLog(
                "Wi-Fi session mode: ${hotspotModeLabel(wirelessHotspotMode)}; " +
                    "applies when settings close",
            )
        }
        section.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val manualFields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        manualFields.addView(
            settingsInputRow("Hotspot SSID", manualHotspotSsid) { value ->
                manualHotspotSsid = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        manualFields.addView(
            settingsChoiceRow(
                label = "Band",
                options = listOf(
                    ManualHotspotBand.AUTO to "Auto",
                    ManualHotspotBand.GHZ_2_4 to "2.4 GHz",
                    ManualHotspotBand.GHZ_5 to "5 GHz",
                ),
                selected = manualHotspotBand,
            ) { value ->
                manualHotspotBand = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsInputRow(
                label = "Channel (0 = auto)",
                value = manualHotspotChannel.toString(),
                numeric = true,
            ) { value ->
                manualHotspotChannel = value.toIntOrNull() ?: -1
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsInputRow(
                label = "Hotspot password",
                value = manualHotspotPassphrase,
                password = true,
            ) { value ->
                manualHotspotPassphrase = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        manualFields.addView(
            settingsChoiceRow(
                label = "Security",
                options = listOf(
                    ManualHotspotSecurity.OPEN to "Open",
                    ManualHotspotSecurity.WPA2 to "WPA2",
                    ManualHotspotSecurity.WPA3_TRANSITION to "WPA3 transition",
                    ManualHotspotSecurity.WPA3 to "WPA3",
                ),
                selected = manualHotspotSecurity,
            ) { value ->
                manualHotspotSecurity = value
                manualHotspotErrorView?.visibility = View.GONE
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) },
        )

        val error = menuText("", 14f, MENU_DANGER).apply {
            visibility = View.GONE
        }
        manualFields.addView(
            error,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )

        section.addView(
            manualFields,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) },
        )
        manualHotspotFields = manualFields
        manualHotspotErrorView = error
        updateManualHotspotFields()
        return section
    }

    private fun updateManualHotspotFields() {
        val visible = wirelessHotspotMode == WirelessHotspotMode.MANUAL
        manualHotspotFields?.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) manualHotspotErrorView?.visibility = View.GONE
    }

    private fun validateMfiSettings(): Boolean {
        val error = when {
            mfiTarget == MfiTarget.I2C && mfiI2cPath.isBlank() ->
                "I2C device path is required"
            mfiTarget == MfiTarget.REMOTE && remoteMfiServer.isBlank() ->
                "Remote server address is required"
            mfiTarget == MfiTarget.REMOTE &&
                !remoteMfiServer.trim().startsWith("http://") &&
                !remoteMfiServer.trim().startsWith("https://") ->
                "Remote server address must start with http:// or https://"
            mfiTarget == MfiTarget.LOCAL_FILES &&
                localMfiCertificateUri.isBlank() != localMfiPrivateKeyUri.isBlank() ->
                "Choose both local MFi documents, or neither"
            mfiTarget == MfiTarget.LOCAL_FILES &&
                localMfiCertificateUri.isBlank() &&
                LocalMfiDocuments.resolve(this) == null ->
                "No local MFi certificate: choose both documents, or push " +
                    "${LocalMfiDocuments.CERTIFICATE_FILE_NAME} + " +
                    "${LocalMfiDocuments.PRIVATE_KEY_FILE_NAME} to " +
                    LocalMfiDocuments.directories(this)
                        .joinToString(" or ") { it.displayPath }
            '\u0000' in mfiI2cPath -> "I2C device path contains U+0000"
            '\u0000' in remoteMfiServer -> "Remote server address contains U+0000"
            '\u0000' in remoteMfiToken -> "Remote token contains U+0000"
            '\u0000' in localMfiCertificateUri -> "Local certificate URI contains U+0000"
            '\u0000' in localMfiPrivateKeyUri -> "Local private key URI contains U+0000"
            else -> null
        }
        mfiErrorView?.text = error.orEmpty()
        mfiErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun validateManualHotspotSettings(): Boolean {
        if (wirelessHotspotMode != WirelessHotspotMode.MANUAL) return true
        val error = when {
            manualHotspotSsid.isBlank() -> "Hotspot SSID is required"
            manualHotspotSsid.encodeToByteArray().size > 32 ->
                "Hotspot SSID must be at most 32 UTF-8 bytes"
            '\u0000' in manualHotspotSsid -> "Hotspot SSID contains U+0000"
            manualHotspotChannel !in 0..196 -> "Channel must be 0 or 1-196"
            manualHotspotChannel != 0 &&
                !isManualHotspotChannelCompatible(manualHotspotBand, manualHotspotChannel) ->
                "Channel is not valid for the selected band"
            '\u0000' in manualHotspotPassphrase -> "Hotspot password contains U+0000"
            manualHotspotSecurity == ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.isNotEmpty() ->
                "Password must be empty when security is Open"
            manualHotspotSecurity != ManualHotspotSecurity.OPEN &&
                manualHotspotPassphrase.length !in 8..63 ->
                "WPA2/WPA3 password must be 8-63 characters"
            else -> null
        }
        manualHotspotErrorView?.text = error.orEmpty()
        manualHotspotErrorView?.visibility = if (error == null) View.GONE else View.VISIBLE
        return error == null
    }

    private fun hotspotModeLabel(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.WIFI_P2P -> "Wi-Fi P2P (5 GHz)"
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> "LocalOnlyHotspot"
        WirelessHotspotMode.MANUAL -> "Manual hotspot"
    }

    private fun menuText(
        text: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) HostUi.monoBold() else HostUi.mono()
        includeFontPadding = false
    }

    private fun updateHotspotStatus(status: CarPlayStatus) {
        if (!wirelessEnabled) return
        hotspotStatus = when (status) {
            CarPlayStatus.StartingHotspot -> HotspotStatus(state = "Starting")
            is CarPlayStatus.HotspotReady -> HotspotStatus(
                state = "Ready",
                ssid = status.ssid,
                band = status.band,
                channel = status.channel,
                backend = status.backend,
                address = status.address,
            )
            CarPlayStatus.WaitingForPairedIphone ->
                hotspotStatus.copy(state = "Waiting for paired iPhone")
            CarPlayStatus.ConnectingBluetooth ->
                hotspotStatus.copy(state = "Connecting Bluetooth")
            CarPlayStatus.RunningWireless ->
                hotspotStatus.copy(state = "Running")
            CarPlayStatus.WirelessActive ->
                hotspotStatus.copy(state = "Active")
            CarPlayStatus.AttachingNetwork ->
                hotspotStatus.copy(state = "Starting AirPlay service")
            is CarPlayStatus.Failed -> hotspotStatus.copy(state = "Error")
            else -> return
        }
        updateHotspotStatusBlock()
    }

    /**
     * The line in the settings header that says the picture behind the panel is a live session: the
     * address the phone is associated with, and what it is being sent. It is the only evidence on
     * screen that opening the menu did not interrupt anything.
     */
    private fun updateSettingsLiveChip() {
        val view = settingsLiveView ?: return
        val running = activeAirPlaySession != null
        val parts = mutableListOf(if (running) "running" else hotspotStatus.state.lowercase())
        hotspotStatus.address?.let { parts.add(it) }
        val transport = if (hevcEnabled) "HEVC" else "H.264"
        val native = activeDisplaySize
        if (native == null) {
            parts.add(transport)
        } else {
            val negotiated = CarPlayDisplayScale.apply(
                AirPlayDisplayConfig(
                    widthPixels = native.width,
                    heightPixels = native.height,
                    widthPhysicalMm = widthPhysicalMm,
                    fps = fps,
                ),
                displayScaleTenths,
            )
            parts.add("$transport ${negotiated.widthPixels}\u00d7${negotiated.heightPixels}")
        }
        view.text = "${if (running) "\u25cf" else "\u25cb"} ${parts.joinToString(" \u00b7 ")}"
        view.setTextColor(if (running) MENU_ACCENT else MENU_FAINT)
    }

    private fun updateHotspotStatusBlock() {
        updateSettingsLiveChip()
        updateIdlePanel()
        if (!wirelessEnabled) {
            hotspotStatusView?.text = "Wireless hotspot: off"
            return
        }
        val status = hotspotStatus
        hotspotStatusView?.text = buildString {
            append("Wireless hotspot: ").append(status.state)
            status.ssid?.let { append("\nSSID: ").append(it) }
            status.backend?.let { append("\nBackend: ").append(it) }
            status.band?.let { append("\nBand: ").append(it) }
            status.channel?.let {
                append("\nChannel: ").append(if (it == 0) "Auto" else it.toString())
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> settingsChoiceRow(
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        onSelected: (T) -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(
            menuText(label, 18f, MENU_LABEL),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        // Two options read as one row of chips; three or more stay stacked, because a chip per
        // option has to fit on the narrowest panel this app runs on.
        val horizontal = options.size <= 2
        val group = RadioGroup(this@CarPlayHostActivity).apply {
            orientation = if (horizontal) RadioGroup.HORIZONTAL else RadioGroup.VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        var selectedId = View.NO_ID
        for ((value, text) in options) {
            val button = RadioButton(this@CarPlayHostActivity).apply {
                id = View.generateViewId()
                this.text = text
                styleChoiceButton(this)
                tag = value
                isChecked = value == selected
            }
            if (value == selected) selectedId = button.id
            val params = if (horizontal) {
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(10) }
            } else {
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
            }
            group.addView(button, params)
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            val value = radioGroup.findViewById<RadioButton>(checkedId)?.tag as? T ?: return@setOnCheckedChangeListener
            onSelected(value)
        }
        addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun updateResolutionMenu() {
        resolutionValueView?.text = CarPlayDisplayScale.label(displayScaleTenths)
        val native = activeDisplaySize ?: currentActivitySize()
        // Four short lines instead of the thirteen this used to be: the switches above already say
        // whether location reporting and audio mapping are on, so the preview only carries what a
        // log or a support question actually needs.
        val resolution = if (native == null) {
            "waiting for the display"
        } else {
            val negotiated = CarPlayDisplayScale.apply(
                AirPlayDisplayConfig(
                    widthPixels = native.width,
                    heightPixels = native.height,
                    widthPhysicalMm = widthPhysicalMm,
                    fps = fps,
                ),
                displayScaleTenths,
            )
            "${native.width}x${native.height} -> " +
                "${negotiated.widthPixels}x${negotiated.heightPixels}"
        }
        val transport = if (!hevcEnabled) {
            "H.264"
        } else {
            "HEVC ${if (hevcSoftwareDecoderEnabled) "sw" else "hw"}"
        }
        updateSettingsLiveChip()
        resolutionPreviewView?.text = buildString {
            append(resolution).append(" @ ").append(fps).append(" fps · ").append(transport)
            append('\n')
            append("identity ").append(normalizedManufacturer()).append(" / ")
                .append(normalizedModel())
                .append(" · oem ").append(oemLabel.ifBlank { "-" })
            append('\n')
            append(widthPhysicalMm).append(" mm ")
                .append(
                    when (physicalSizeBasis) {
                        AirPlayPhysicalSizeBasis.WIDTH -> "widest"
                        AirPlayPhysicalSizeBasis.HEIGHT -> "longest"
                    },
                )
                .append(" · driving ").append(if (rightHandDrive) "right" else "left")
                .append(" · ")
                .append(if (hideTopBar) "top hidden" else "top shown")
                .append(", ")
                .append(if (hideBottomBar) "bottom hidden" else "bottom shown")
            append('\n')
            append("max ").append(maximumDetectedWidthPixels).append("x")
                .append(maximumDetectedHeightPixels)
            native?.let { size ->
                val physical = resolvePhysicalSize(size)
                append(" · carplay ").append(physical.widthMm).append("x")
                    .append(physical.heightMm).append(" mm")
            }
            append('\n')
            append(safeAreaSummary())
        }
    }

    private fun createAirPlayConfig(size: DisplaySize): AirPlayConfig {
        val physical = resolvePhysicalSize(size)
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = size.width,
            heightPixels = size.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
        )
        val scaledDisplay = CarPlayDisplayScale.apply(
            baseDisplay,
            displayScaleTenths,
        )
        val display = scaledDisplay.copy(
            safeArea = AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height),
                activityWidthPixels = size.width,
                activityHeightPixels = size.height,
                displayWidthPixels = scaledDisplay.widthPixels,
                displayHeightPixels = scaledDisplay.heightPixels,
            ),
            safeAreaDrawOutside = safeAreaDrawOutside,
        )
        return AirPlayConfig(
            deviceName = "NEVOPlay",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:01",
            sourceVersion = "950.7.1",
            main = display,
            rightHandDrive = rightHandDrive,
            hevc = hevcEnabled,
            microphone = microphoneAvailable,
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = normalizedOemLabel(),
            icons = listOf(loadAirPlayIcon()),
        )
    }

    private fun loadAirPlayIcon(): AirPlayIcon {
        val customBytes = try {
            AirPlayPersistence.loadCustomAirPlayIconFile(this)?.readBytes()
        } catch (_: Exception) {
            null
        }
        if (customBytes != null) {
            decodeAirPlayIcon(customBytes)?.let { return it }
            AirPlayPersistence.clearCustomAirPlayIcon(this)
        }
        return decodeAirPlayIcon(defaultAirPlayIconBytes())
            ?: throw IllegalStateException("Packaged AirPlay icon is invalid")
    }

    private fun decodeAirPlayIcon(encoded: ByteArray): AirPlayIcon? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
            bounds.outWidth != bounds.outHeight
        ) {
            return null
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    private fun defaultAirPlayIconBytes(): ByteArray =
        resources.openRawResource(R.raw.placeholder_icon).use { it.readBytes() }

    private fun updateAirPlayIconPreview() {
        val preview = iconPreviewView ?: return
        val custom = AirPlayPersistence.loadCustomAirPlayIconFile(this)
        var customBitmap: Bitmap? = null
        if (custom != null) {
            customBitmap = BitmapFactory.decodeFile(custom.absolutePath)
            if (customBitmap == null) {
                AirPlayPersistence.clearCustomAirPlayIcon(this)
            }
        }
        val bitmap = customBitmap ?: BitmapFactory.decodeResource(resources, R.raw.placeholder_icon)
        preview.setImageBitmap(bitmap)
        iconStatusView?.text =
            if (customBitmap != null) "Custom 1:1 icon" else "Default placeholder icon"
    }

    private fun currentActivitySize(): DisplaySize? {
        val view = videoView
        if (view != null && view.width > 0 && view.height > 0) {
            return DisplaySize(view.width, view.height)
        }
        return activeDisplaySize
    }

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = maxOf(maximumDetectedWidthPixels, size.width),
            maximumHeightPixels = maxOf(maximumDetectedHeightPixels, size.height),
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )

    private fun safeAreaSummary(): String {
        val size = currentActivitySize() ?: return "Safe area: waiting for activity size"
        val mapping = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
        return if (mapping == null) {
            "Safe area: full screen at ${size.width} x ${size.height}"
        } else {
            "Safe area: ${mapping.width} x ${mapping.height} at " +
                "(${mapping.left}, ${mapping.top}) in ${size.width} x ${size.height}"
        }
    }

    private fun updateSafeAreaSummary() {
        safeAreaSummaryView?.text = safeAreaSummary()
    }

    private fun openSafeAreaEditor() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area editor is unavailable before display layout")
            return
        }
        val editorView = safeAreaEditorView ?: return
        val initial = AirPlayPersistence.loadSafeAreaRect(this, size.width, size.height)
            ?: AirPlaySafeArea.default(size.width, size.height)
        safeAreaEditSize = size
        safeAreaEditorActive = true
        // Keep the current activity size; changing system bars here would remap the safe area.
        settingsMenu?.visibility = View.GONE
        safeAreaEditor?.visibility = View.VISIBLE
        editorView.setRect(initial, size.width, size.height)
        appendLog(
            "Safe area editor opened for ${size.width}x${size.height}; " +
                "drag the four boundaries",
        )
    }

    private fun closeSafeAreaEditor() {
        if (!safeAreaEditorActive) return
        safeAreaEditorActive = false
        safeAreaEditSize = null
        safeAreaEditor?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area editor closed")
    }

    private fun saveSafeAreaEditor() {
        val size = safeAreaEditSize ?: currentActivitySize() ?: return
        val rect = safeAreaEditorView?.currentRectForSource() ?: return
        AirPlayPersistence.saveSafeAreaRect(this, size.width, size.height, rect)
        appendLog(
            "Safe area saved for ${size.width}x${size.height}: " +
                "${rect.width}x${rect.height} at (${rect.left}, ${rect.top})",
        )
        closeSafeAreaEditor()
    }

    private fun resetSafeAreaForCurrentSize() {
        val size = currentActivitySize()
        if (size == null) {
            appendLog("Safe area reset is unavailable before display layout")
            return
        }
        AirPlayPersistence.clearSafeAreaRect(this, size.width, size.height)
        updateSafeAreaSummary()
        updateResolutionMenu()
        appendLog("Safe area reset to full screen for ${size.width}x${size.height}")
    }

    private fun refreshDisplaySizeAfterLayout() {
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    private fun normalizedModel(): String =
        model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    private fun normalizedOemLabel(): String =
        oemLabel.trim().ifBlank { AirPlayPersistence.DEFAULT_OEM_LABEL }

    private fun createMediaSink(
        videoWidth: Int,
        videoHeight: Int,
        controllerGeneration: Int,
    ): AndroidMediaSink = AndroidMediaSink(
        context = applicationContext,
        surface = null,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        preferSoftwareHevcDecoder = hevcSoftwareDecoderEnabled,
        advancedAudioChannelMapping = advancedAudioChannelMapping,
        mainMediaAudioBufferDurationMs = mainMediaAudioBufferDurationMs,
        mediaMetricsMonitor = mediaMetricsMonitor,
        microphoneGainPercent = microphoneGainPercent,
        onScreenStreamActiveChanged = { type, active ->
            onScreenStreamStateChanged(controllerGeneration, type, active)
        },
        onVideoFrameRendered = { onVideoFrameRendered(controllerGeneration) },
        onMediaDiagnostic = { message ->
            runOnUiThread {
                if (!shuttingDown.get() && controllerGeneration == restartGeneration) {
                    appendLog(message)
                }
            }
        },
    )

    private fun createMediaEngine(sink: AndroidMediaSink): CarPlayMediaEngine =
        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneAvailable,
            audioCaptureDirectory = audioCaptureDirectory(),
        )

    private fun createSessionListener(controllerGeneration: Int): AirPlaySessionListener =
        object : AirPlaySessionListener {
            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeAirPlaySession = session
                    // A live session is the proof that the retries worked: start counting again.
                    consecutiveReconnectFailures = 0
                    hotspotTroubleReported = false
                    updateSettingsLiveChip()
                    syncAirPlayDarkMode()
                    if (menuOpen) return@runOnUiThread
                    appendLog("AirPlay session active")
                }
            }

            override fun onLinkSilent(session: AirPlaySession, silentMs: Long) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) return@runOnUiThread
                    linkSilent = true
                    // Not a reconnect: the session is left alone, so a link that comes back keeps its
                    // CarPlay session. This only stops the last frame being presented as if it were
                    // live, which is what "stuck on the CarPlay screen" actually was.
                    if (!menuOpen) setConnectionStage("CarPlay link quiet; waiting for the phone")
                    appendLog("AirPlay link silent for ${silentMs / 1000}s; covering the last frame")
                    updateDebugOverlays()
                }
            }

            override fun onLinkActive(session: AirPlaySession, resumedAfterMs: Long) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) return@runOnUiThread
                    linkSilent = false
                    appendLog("AirPlay link resumed after ${resumedAfterMs / 1000}s")
                    updateDebugOverlays()
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    if (activeAirPlaySession === session) activeAirPlaySession = null
                    updateSettingsLiveChip()
                    if (menuOpen || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    linkSilent = false
                    setConnectionStage("CarPlay session ended; reconnecting")
                    appendLog("AirPlay session ended; reconnecting from scratch")
                    reconnectAfterLoss("AirPlay session ended")
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (menuOpen || controllerGeneration != restartGeneration) {
                        return@runOnUiThread
                    }
                    activeScreenStreamTypes.clear()
                    setConnectionStage("Transport error; reconnecting")
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override fun onHostUiRequested(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != restartGeneration) return@runOnUiThread
                    appendLog("CarPlay asked for the car UI; opening the head unit home screen")
                    showHeadUnitHome()
                }
            }

            override fun onDebugLog(message: String) {
                val now = System.currentTimeMillis()
                appendFileLog(message, now)
                if (!debugLogsEnabled || message.startsWith(PROTOCOL_TRACE_PREFIX)) return
                synchronized(pendingScreenLogsLock) {
                    if (!debugLogsEnabled) return
                    pendingScreenLogs.addLast(PendingLog(controllerGeneration, now, message))
                    if (pendingScreenLogs.size > MAX_SCREEN_LOG_LINES) pendingScreenLogs.removeFirst()
                    if (!screenDrainPosted) {
                        screenDrainPosted = true
                        mainHandler.post(drainScreenLogs)
                    }
                }
            }
        }

    private fun createStatusReporter(
        controllerGeneration: Int,
    ): (CarPlayStatus) -> Unit = { status ->
        if (!menuOpen && controllerGeneration == restartGeneration) {
            updateHotspotStatus(status)
            val description = status.describe()
            setConnectionStage(withHotspotNotice(description))
            when (status) {
                is CarPlayStatus.Failed -> reconnectAfterLoss(description)
                else -> Unit
            }
        }
    }

    private fun adoptBackgroundSession(): Boolean {
        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed()) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            return false
        }
        controller = snapshot.controller
        sink = snapshot.sink
        snapshot.sink.setMediaMetricsMonitor(mediaMetricsMonitor)
        if (snapshot.width > 0 && snapshot.height > 0) {
            activeDisplaySize = DisplaySize(snapshot.width, snapshot.height)
            controllerDisplaySize = activeDisplaySize
        }
        val generation = restartGeneration
        snapshot.controller.attachUi(
            createSessionListener(generation),
            createStatusReporter(generation),
        )
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(restartGeneration, type, active)
        }
        snapshot.sink.setVideoFrameRenderedListener { onVideoFrameRendered(restartGeneration) }
        currentSurface?.let(::attachSurface)
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        appendLog(
            if (serviceReused) {
                "Reusing existing background CarPlay service"
            } else {
                "Reusing existing background CarPlay session"
            },
        )
        setConnectionStage(
            if (serviceReused) {
                "CarPlay service already running"
            } else {
                "CarPlay session already running"
            },
        )
        updateDebugOverlays()
        return true
    }

    private fun startCarPlay(size: DisplaySize) {
        if (shuttingDown.get() || menuOpen || stackRebuildInProgress || controller != null) return
        val controllerGeneration = restartGeneration
        controllerDisplaySize = size
        val config = createRuntimeConfig()
        val airPlayConfig = createAirPlayConfig(size)
        val locationProvider: Iap2LocationProvider? =
            if (config.locationReportingEnabled) {
                AndroidCarPlayLocationProvider(this)
            } else {
                null
            }
        appendLog(
            "Starting CarPlay controller at ${size.width}x${size.height} -> " +
                "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "(${CarPlayDisplayScale.label(displayScaleTenths)}) " +
                "physical=${airPlayConfig.main.widthPhysicalMm}x" +
                "${airPlayConfig.main.heightPhysicalMm}mm " +
                "video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} " +
                "decoder=${if (airPlayConfig.hevc && hevcSoftwareDecoderEnabled) "software" else "hardware"} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${if (config.locationReportingEnabled) "enabled" else "disabled"} " +
                "mfi=${mfiTargetLabel(config.mfiTarget)}",
        )
        Log.i(
            TAG,
            "starting controller display=${size.width}x${size.height} " +
                "negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "scale=${CarPlayDisplayScale.label(displayScaleTenths)} " +
                "hevc=${airPlayConfig.hevc} " +
                "softwareHevc=${airPlayConfig.hevc && hevcSoftwareDecoderEnabled} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${config.locationReportingEnabled} " +
                "mfi=${config.mfiTarget}",
        )
        val renderer = createMediaSink(
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            controllerGeneration = controllerGeneration,
        )
        sink = renderer
        currentSurface?.let(::attachSurface)
        val media = createMediaEngine(renderer)
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = createSessionListener(controllerGeneration),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration),
            loadPairRecord = { AirPlayPersistence.loadLockdownRecord(this) },
            savePairRecord = { record -> AirPlayPersistence.saveLockdownRecord(this, record) },
            clearPairRecord = { AirPlayPersistence.clearLockdownRecord(this) },
            locationProvider = locationProvider,
        )
        controller = next
        CarPlayBackgroundSession.store(next, renderer, size.width, size.height)
        next.start()
    }

    private fun syncAirPlayDarkMode() {
        val session = activeAirPlaySession ?: return
        val night = darkMode
        airPlayCommandExecutor.execute {
            try {
                val sent = session.setNightMode(night)
                Log.i(
                    TAG,
                    "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent",
                )
                // Also into the session log: this line is the proof of what we asked the phone for,
                // and the logcat tap that carries Log.i provably loses lines.
                runOnUiThread {
                    appendLog(
                        "AirPlay night mode sent night=$night eventChannelReady=$sent",
                    )
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
    }

    private fun audioCaptureDirectory(): File? {
        // Two ways in on purpose: the settings switch, and the marker file an adb `run-as` session can
        // create on a build nobody is sitting in front of.
        if (!audioPacketCaptureEnabled && !File(filesDir, AUDIO_CAPTURE_MARKER).isFile) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return
        val size = DisplaySize(width, height)
        if (size == activeDisplaySize) {
            // The window came back to the size we already run at: drop the queued change instead of
            // re-handshaking twice for a resize that has been undone (a vehicle status bar that
            // appears and disappears, a display that renegotiates twice, a rotation that reverts).
            if (pendingDisplaySize != null) {
                pendingDisplaySize = null
                mainHandler.removeCallbacks(applyDisplaySize)
                appendLog("Display returned to ${size.width}x${size.height}; pending change dropped")
                noteDisplayRevert()
            }
            return
        }
        if (size == pendingDisplaySize) return
        pendingDisplaySize = size
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.postDelayed(applyDisplaySize, DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    /**
     * Counts a resize that was undone inside the debounce window. A head unit that toggles its own
     * status bar can do this several times a second, and on such a device the insets never admit
     * that the bar is visible, so the insets-based latch never arms. The size signal is the one that
     * demonstrably fires, so the decision to stop touching the bars is taken here instead: after a
     * burst of reverts the app stops asserting fullscreen, which is what ends the oscillation.
     */
    private fun noteDisplayRevert() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastDisplayRevertUptime > DISPLAY_REVERT_STRIKE_WINDOW_MILLIS) {
            displayRevertStrikes = 0
        }
        lastDisplayRevertUptime = now
        displayRevertStrikes++
        if (displayRevertStrikes < DISPLAY_REVERT_STRIKES || now < displayYieldUntilUptime) return
        displayYieldUntilUptime = now + DISPLAY_YIELD_MILLIS
        appendLog(
            "Window size is oscillating (${displayRevertStrikes} reverts); leaving the system bars " +
                "alone for ${DISPLAY_YIELD_MILLIS / 1000}s",
        )
    }

    private fun applyDisplaySize(size: DisplaySize) {
        if (shuttingDown.get() || size == activeDisplaySize) return
        val previous = activeDisplaySize
        activeDisplaySize = size
        recordDetectedMaximum(size)
        updateResolutionMenu()
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (menuOpen || stackRebuildInProgress) {
            // The menu covers the picture, and a rebuild is already under way: record it only. The
            // menu's exit path renegotiates if the window it went in with is not the window it comes
            // out of.
            appendLog(
                "Display updated out of sight: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        } else {
            restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() {
        if (controller == null && adoptBackgroundSession()) return
        val size = activeDisplaySize ?: return
        val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
        val locationReady = !locationReportingEnabled || locationPermissionAvailable
        if (
            !transportReady ||
            !locationReady ||
            !microphonePermissionResolved ||
            shuttingDown.get() ||
            menuOpen ||
            stackRebuildInProgress ||
            controller != null
        ) {
            return
        }
        startCarPlay(size)
    }

    /**
     * Tells the driver what can be done about a Wi-Fi P2P state machine that keeps refusing to
     * create a group. Reaching into Settings to toggle Wi-Fi is not something this app may do, and
     * repeating the same failed attempt every two seconds is not a recovery - the framework frees
     * the old group when it gets around to it. So say it once, on the stage banner where the missing
     * picture would have been, name the one action that lives in this app, and keep polling slowly
     * in the background. Rebooting the head unit is deliberately not offered: it cannot be done while
     * driving, and the retained group is not what a reboot would be needed for.
     */
    private fun reportHotspotTrouble() {
        if (hotspotTroubleReported || consecutiveReconnectFailures < HOTSPOT_TROUBLE_AFTER_FAILURES) {
            return
        }
        hotspotTroubleReported = true
        setStatus(
            "Wi-Fi P2P is stuck after $consecutiveReconnectFailures attempts; " +
                "tap Connect to iPhone, or switch the head unit's Wi-Fi off and on once",
        )
    }

    /** Adds the recovery hint to every following failure line while P2P is stuck. */
    private fun withHotspotNotice(description: String): String =
        if (hotspotTroubleReported && description.contains("Wi-Fi P2P", ignoreCase = true)) {
            "$description - tap Connect to iPhone, or switch Wi-Fi off and on once"
        } else {
            description
        }

    private fun reconnectAfterLoss(reason: String) {
        if (shuttingDown.get() || menuOpen || stackRebuildInProgress) return
        if (reconnectScheduled) return
        reconnectScheduled = true
        val generation = restartGeneration
        if (reason.contains("Wi-Fi P2P", ignoreCase = true)) reportHotspotTrouble()
        val failures = consecutiveReconnectFailures
        consecutiveReconnectFailures = failures + 1
        // A phone that walked away comes back to the group it already knows, so the live hotspot is
        // handed to the next controller instead of being torn down and rebuilt - that churn is what
        // the framework answers BUSY to. A stack that failed on its own gets a clean group instead.
        val retainHotspot = wirelessEnabled && keepsHotspotAcrossRebuild(reason)
        val delayMillis = if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            reconnectBackoffMillis(failures)
        }
        appendLog("$reason; retrying in ${delayMillis}ms")
        val pending = Runnable {
            pendingReconnect = null
            reconnectScheduled = false
            if (
                shuttingDown.get() ||
                menuOpen ||
                stackRebuildInProgress ||
                generation != restartGeneration
            ) {
                return@Runnable
            }
            restartCarPlay("Reconnecting after $reason", retainHotspot = retainHotspot)
        }
        pendingReconnect = pending
        mainHandler.postDelayed(pending, delayMillis)
    }

    /**
     * Starts one fresh wireless handshake now, instead of waiting out the backoff.
     *
     * This is the whole answer to "I walked away with the phone and came back": the app keeps the
     * advertisement - and the group the phone already knows - alive and waits, and this row lets the
     * driver ask for the handshake immediately. The live hotspot is handed to the next controller
     * rather than rebuilt, so the phone re-associates to the network it remembers.
     */
    private fun connectToIphoneNow() {
        if (shuttingDown.get() || !wirelessEnabled) return
        if (stackRebuildInProgress) {
            appendLog("Connect to iPhone ignored; a rebuild is already in progress")
            return
        }
        if (activeAirPlaySession != null) {
            appendLog("Connect to iPhone ignored; a CarPlay session is already up")
            return
        }
        if (activeDisplaySize == null) {
            appendLog("Connect to iPhone ignored; the display size is not known yet")
            return
        }
        pendingReconnect?.let { mainHandler.removeCallbacks(it) }
        pendingReconnect = null
        reconnectScheduled = false
        consecutiveReconnectFailures = 0
        hotspotTroubleReported = false
        appendLog("Connect to iPhone tapped; reconnecting now")
        restartCarPlay("Connecting to iPhone", retainHotspot = true)
    }

    /**
     * Rebuilds the whole stack.
     *
     * [retainHotspot] hands the Wi-Fi group the iPhone is associated with to the new controller, so
     * a settings-driven reconnect comes back on the address the phone already knows instead of
     * making it re-associate. It is only useful while this host stays wireless, where the same
     * group will be taken over again.
     */
    private fun restartCarPlay(reason: String, retainHotspot: Boolean = false) {
        if (shuttingDown.get() || menuOpen || stackRebuildInProgress) return
        val size = activeDisplaySize ?: return
        appendLog(reason)
        activeScreenStreamTypes.clear()
        setConnectionStage(reason)
        Log.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        stackRebuildInProgress = true
        val generation = ++restartGeneration
        val oldController = controller
        val oldSink = sink
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        sink = null
        teardownExecutor.execute {
            oldController?.close(retainWirelessHotspot = retainHotspot)
            oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
            oldSink?.close()
            runOnUiThread {
                if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
                stackRebuildInProgress = false
                startCarPlay(size)
            }
        }
    }

    /**
     * Opens the settings overlay **over** a running session.
     *
     * Nothing here touches the controller: the picture keeps decoding behind the menu, the phone
     * keeps its association, and a look at the settings costs nothing. Only "Save & Reconnect"
     * starts a new handshake - the AirPlay listener carries the resolution, the codec and the MFi
     * channel, so those are the settings that need one.
     */
    private fun openSettingsMenu() {
        if (menuOpen || shuttingDown.get()) return
        stopMicrophoneGainTest()
        settingsBaseline = captureSettingsBaseline()
        menuOpen = true
        gestureOverlay?.visibility = View.GONE
        settingsMenu?.visibility = View.VISIBLE
        syncMicrophoneGainControls()
        syncMainMediaAudioBufferControls()
        // Re-probe the local MFi directories on every open: the files are pushed over adb while the
        // app is running, and the section is built once.
        refreshLocalMfiSources()
        updateDebugOverlays()
        clearScreenLogs()
        appendLog("Settings opened over the running session")
        updateResolutionMenu()
    }

    private fun saveSettingsAndReconnect() {
        if (!menuOpen) return
        if (!validateMfiSettings()) return
        if (!validateManualHotspotSettings()) return
        persistMenuSettings()
        settingsBaseline = null
        closeSettingsMenu(
            "Settings saved; re-establishing the handshake at " +
                "${CarPlayDisplayScale.label(displayScaleTenths)} with " +
                (if (hevcEnabled) "HEVC (H.265)" else "H.264") +
                ", MFI ${mfiTargetLabel(mfiTarget)}" +
                ", Wi-Fi session ${hotspotModeLabel(wirelessHotspotMode)}",
        )
        restartCarPlay(
            "Reconnecting after settings",
            retainHotspot = wirelessEnabled,
        )
    }

    /**
     * Leaving the menu without saving: the settings never reached the session, so there is nothing
     * to reconnect for - the picture that has been running behind the menu simply stays.
     */
    private fun cancelSettingsEdits() {
        if (!menuOpen) return
        restoreSettingsBaseline()
        closeSettingsMenu("Settings changes discarded; the running session was left alone")
        // A session that died while the menu was open has no reconnection scheduled - the retry path
        // is deliberately quiet while the menu covers the screen - so pick it up here.
        resumeCarPlayAfterSettings()
    }

    private fun closeSettingsMenu(prefix: String) {
        if (!menuOpen) return
        stopMicrophoneGainTest()
        menuOpen = false
        settingsMenu?.visibility = View.GONE
        gestureOverlay?.visibility = View.VISIBLE
        updateDebugOverlays()
        clearScreenLogs()
        appendLog(prefix)
    }

    /**
     * Brings the session that ran behind the menu back into shape after an unsaved exit, without
     * touching it when nothing happened to it: a session that died while the menu covered the screen
     * has no reconnection scheduled, and a window that changed size behind it still runs at the old
     * one.
     */
    private fun resumeCarPlayAfterSettings() {
        if (shuttingDown.get()) return
        if (controller == null) {
            // Either nothing was running yet or a rebuild that was in flight when the menu opened had
            // its start dropped by the menu guard; start the normal way.
            maybeStartCarPlay()
            return
        }
        if (activeAirPlaySession == null) {
            restartCarPlay(
                "CarPlay session was lost while settings were open; reconnecting",
                retainHotspot = wirelessEnabled,
            )
            return
        }
        val size = activeDisplaySize
        if (size != null && size != controllerDisplaySize) {
            restartCarPlay("Display changed while settings were open", retainHotspot = wirelessEnabled)
        }
    }

    private fun exitApplication() {
        if (shuttingDown.get()) return
        restoreSettingsBaseline()
        finishAndRemoveTask()
        shutdown(terminateProcess = true, reason = "settings exit application")
    }

    private fun shutdown(terminateProcess: Boolean, reason: String) {
        if (!shuttingDown.compareAndSet(false, true)) return
        stopMicrophoneGainTest()
        restartGeneration += 1
        mainHandler.removeCallbacks(applyDisplaySize)
        val oldController = controller
        val oldSink = sink
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        sink = null
        Log.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            oldController?.close()
            val clean = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            oldSink?.close()
            // Exiting from inside the settings menu leaves no controller: the hotspot it parked for
            // the next handshake has to be removed here, or it outlives the app.
            CarPlayController.releaseRetainedWirelessHotspot()
            airPlayCommandExecutor.shutdown()
            if (terminateProcess) {
                applicationContext.stopService(Intent(applicationContext, CarPlayVpnService::class.java))
            }
            Log.i(TAG, "shutdown complete clean=$clean")
            teardownExecutor.shutdown()
            if (terminateProcess) Process.killProcess(Process.myPid())
        }
    }

    private fun attachSurface(surface: Surface) {
        sink?.setSurface(SCREEN_TYPE_MAIN, surface)
        sink?.setSurface(SCREEN_TYPE_ALT, surface)
    }

    /**
     * Every touch in the window passes here before the view under it does, which is what a gesture
     * that has to work on any screen needs: on the settings overlay [onHostTouch] never runs, because
     * the menu covers the video view that owns it.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (
            menuOpen &&
            trackThreeFingerTap(event, releaseForwardedContacts = false) { dismissSettingsMenu() }
        ) {
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    /** Leaves the settings overlay the way the back control does, from the three-finger gesture. */
    private fun dismissSettingsMenu() {
        if (!menuOpen) return
        if (safeAreaEditorActive) {
            closeSafeAreaEditor()
        } else {
            cancelSettingsEdits()
        }
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean {
        if (menuOpen) return true

        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            edgeSettingsGestureCaptured = moreGesturesToSettings &&
                event.x in 0f..(view.width / 8f) &&
                event.y in 0f..(view.height / 4f)
            edgeSettingsGestureEligible = edgeSettingsGestureCaptured
        }

        // Two three-finger taps in a row open settings from the picture; the same gesture on the
        // settings overlay leaves it again (handled in dispatchTouchEvent).
        if (trackThreeFingerTap(event, releaseForwardedContacts = true) { openSettingsMenu() }) {
            edgeSettingsGestureCaptured = false
            edgeSettingsGestureEligible = false
            return true
        }

        if (edgeSettingsGestureCaptured) {
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> edgeSettingsGestureEligible = false
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount != 1 || event.x !in 0f..(view.width / 8f)) {
                        edgeSettingsGestureEligible = false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val openSettings = edgeSettingsGestureEligible &&
                        event.x in 0f..(view.width / 8f) &&
                        event.y in (view.height * 3f / 4f)..view.height.toFloat()
                    edgeSettingsGestureCaptured = false
                    edgeSettingsGestureEligible = false
                    if (openSettings) openSettingsMenu()
                }
                MotionEvent.ACTION_CANCEL -> {
                    edgeSettingsGestureCaptured = false
                    edgeSettingsGestureEligible = false
                }
            }
            return true
        }

        val contacts = CarPlayTouchMapper.contacts(event, view.width, view.height)
        val queued = controller?.sendTouch(contacts) ?: false
        if (queued) {
            touchReportsSinceStats++
            scheduleTouchStats()
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> Log.i(
                TAG,
                "touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                    "pointers=${event.pointerCount} queued=$queued",
            )
        }
        return true
    }

    /**
     * Tracks the three-finger double tap for whichever surface is receiving touches.
     *
     * Returns true while the contact belongs to the gesture, and the caller must then swallow it -
     * the first finger has already reached whatever is under it. [onDoubleTap] runs once, after the
     * second tap's fingers leave the screen. [releaseForwardedContacts] takes that first finger back
     * from the phone, which only the CarPlay surface needs because only there has it been sent.
     */
    private fun trackThreeFingerTap(
        event: MotionEvent,
        releaseForwardedContacts: Boolean,
        onDoubleTap: () -> Unit,
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureTapSwallowed = false
                gestureTapCandidate = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == THREE_FINGER_TAP_COUNT && !gestureTapSwallowed) {
                    gestureTapSwallowed = true
                    gestureTapCandidate = true
                    gestureTapStartX = pointerCentroid(event, horizontal = true)
                    gestureTapStartY = pointerCentroid(event, horizontal = false)
                    gestureTapDownTime = event.downTime
                    if (releaseForwardedContacts) controller?.sendTouch(emptyList())
                    appendLog(
                        "Three-finger tap ${gestureTapsSeen + 1}/$THREE_FINGER_TAPS_TO_SETTINGS " +
                            "tracking",
                    )
                    return true
                }
                if (gestureTapSwallowed) gestureTapCandidate = false
            }
        }

        if (!gestureTapSwallowed) return false

        // A tap is judged when the first of the three fingers leaves, which is also the moment the
        // contact stops being three-fingered.
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != THREE_FINGER_TAP_COUNT ||
                    Math.abs(
                        pointerCentroid(event, horizontal = true) - gestureTapStartX,
                    ) > dp(THREE_FINGER_TAP_SLOP_DP) ||
                    Math.abs(
                        pointerCentroid(event, horizontal = false) - gestureTapStartY,
                    ) > dp(THREE_FINGER_TAP_SLOP_DP)
                ) {
                    // Fingers that travel are a drag, not a tap.
                    gestureTapCandidate = false
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount == THREE_FINGER_TAP_COUNT) {
                    judgeThreeFingerTap(event.eventTime, onDoubleTap)
                }
            }
            MotionEvent.ACTION_UP -> {
                judgeThreeFingerTap(event.eventTime, onDoubleTap)
                gestureTapSwallowed = false
                gestureTapCandidate = false
            }
            MotionEvent.ACTION_CANCEL -> {
                gestureTapSwallowed = false
                gestureTapCandidate = false
            }
        }
        return true
    }

    /**
     * Counts one three-finger tap and reports the double tap on the last of the pair.
     *
     * A tap has to be short and still; the fingers of the second tap may land anywhere, only the
     * gap between the two taps is limited, because three fingers cannot be placed as fast as one.
     */
    private fun judgeThreeFingerTap(upTime: Long, onDoubleTap: () -> Unit) {
        if (!gestureTapCandidate) return
        gestureTapCandidate = false
        if (upTime - gestureTapDownTime > THREE_FINGER_TAP_MAX_MILLIS) return
        gestureTapsSeen = if (upTime - gestureTapLastUpTime <= THREE_FINGER_DOUBLE_TAP_WINDOW_MILLIS) {
            gestureTapsSeen + 1
        } else {
            1
        }
        gestureTapLastUpTime = upTime
        if (gestureTapsSeen >= THREE_FINGER_TAPS_TO_SETTINGS) {
            gestureTapsSeen = 0
            gestureTapLastUpTime = 0L
            appendLog("Three-finger double tap")
            onDoubleTap()
        } else {
            appendLog("Three-finger tap $gestureTapsSeen/$THREE_FINGER_TAPS_TO_SETTINGS")
        }
    }

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

    /** Logs how many touch reports were forwarded, once per window and only when there were any. */
    private fun scheduleTouchStats() {
        if (touchStatsPosted) return
        touchStatsPosted = true
        mainHandler.postDelayed(
            {
                touchStatsPosted = false
                val reports = touchReportsSinceStats
                val logLines = logLinesSinceStats
                touchReportsSinceStats = 0
                logLinesSinceStats = 0
                appendLog(
                    "load stats touchReports=$reports logLines=$logLines " +
                        "window=${TOUCH_STATS_WINDOW_MILLIS}ms",
                )
            },
            TOUCH_STATS_WINDOW_MILLIS,
        )
    }

    /**
     * Forwards the sink's first rendered frame to the controller that owns this generation. The
     * controller's wireless watchdog uses it to tell "the tunnel never opened" from "nothing works
     * yet", and leaves a session that is already on screen alone.
     */
    private fun onVideoFrameRendered(generation: Int) {
        runOnUiThread {
            if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
            controller?.onVideoFrameRendered()
        }
    }

    private fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (shuttingDown.get() || generation != restartGeneration) return@runOnUiThread
            if (active) {
                activeScreenStreamTypes.add(type)
            } else {
                activeScreenStreamTypes.remove(type)
            }
            // The stage banner ("...control running") is only hidden while a screen stream is
            // considered active, so whether the host noticed the stream is part of the picture
            // problem and belongs in the log.
            appendLog(
                "screen stream ${if (active) "active" else "inactive"} type=$type " +
                    "active=$activeScreenStreamTypes",
            )
            updateDebugOverlays()
        }
    }

    private fun setStatus(message: String) {
        runOnUiThread {
            setConnectionStage(message)
            appendLog(message)
        }
    }

    private fun setConnectionStage(message: String) {
        latestStage = message
        stageStatusView?.text = message
        updateDebugOverlays()
    }

    private fun updateDebugOverlays() {
        val showLogs = debugLogsEnabled && !menuOpen
        statusScrollView?.visibility = if (showLogs) View.VISIBLE else View.GONE
        // A silent link means the picture is stale even though the stream is still nominally set
        // up, so it counts as idle: the CarPlay session survives, only the presentation changes.
        val idle = !menuOpen && (activeScreenStreamTypes.isEmpty() || linkSilent)
        idlePanel?.visibility = if (idle) View.VISIBLE else View.GONE
        // Same condition: the picture is only worth presenting while a stream is active and the link
        // is quiet-free. The stream state itself only flips at session boundaries (8 times active and
        // 5 inactive across a day of logs), so the only thing that moves this mid-session is the
        // 8-second silence notice - which is the point of it.
        videoBackdropView?.visibility = if (idle) View.VISIBLE else View.GONE
        updateIdlePanel()
        val showStage = idle
        stageStatusView?.visibility = if (showStage) View.VISIBLE else View.GONE
        // The build stamp sits under the picture, not on top of it: as soon as a screen stream is
        // active the CarPlay image is what the driver should see.
        buildLabelView?.visibility = if (idle) View.VISIBLE else View.GONE
        if (showStage != lastStageOverlayShown) {
            lastStageOverlayShown = showStage
            appendLog(
                "stage banner ${if (showStage) "shown" else "hidden"} " +
                    "debugLogs=$debugLogsEnabled menu=$menuOpen active=$activeScreenStreamTypes",
            )
        }
        updateMediaMetricsOverlay()
    }

    /**
     * Attaches, detaches or hides the latency chart. It follows the same rule as the other overlays:
     * never on top of the picture while the settings panel is open or the safe-area editor is up.
     */
    private fun updateMediaMetricsOverlay() {
        val root = contentRoot as? ViewGroup ?: return
        if (!mediaMetricsEnabled) {
            sink?.setMediaMetricsMonitor(null)
            mediaMetricsOverlay?.let(root::removeView)
            mediaMetricsOverlay = null
            mediaMetricsMonitor = null
            return
        }
        val monitor = mediaMetricsMonitor ?: MediaMetricsMonitor().also {
            mediaMetricsMonitor = it
        }
        sink?.setMediaMetricsMonitor(monitor)
        // The frame-rate axis follows the configured target, which the settings menu can change
        // while this overlay stays alive.
        val overlay = mediaMetricsOverlay ?: MediaMetricsOverlayView(
            this,
            monitor,
            fpsAxisMax = { fps.toFloat() },
        ).also {
            mediaMetricsOverlay = it
            val settingsIndex = root.indexOfChild(settingsMenu)
            root.addView(
                it,
                if (settingsIndex >= 0) settingsIndex else root.childCount,
                mediaMetricsOverlayLayoutParams(root.width, root.height),
            )
        }
        overlay.visibility = if (!menuOpen && !safeAreaEditorActive) View.VISIBLE else View.GONE
        updateMediaMetricsOverlayLayout(root.width, root.height)
    }

    private fun updateMediaMetricsOverlayLayout(screenWidth: Int, screenHeight: Int) {
        val overlay = mediaMetricsOverlay ?: return
        val desired = mediaMetricsOverlayLayoutParams(screenWidth, screenHeight)
        val current = overlay.layoutParams
        if (current.width != desired.width || current.height != desired.height) {
            overlay.layoutParams = desired
        }
    }

    private fun mediaMetricsOverlayLayoutParams(
        screenWidth: Int,
        screenHeight: Int,
    ): FrameLayout.LayoutParams {
        val fallback = resources.displayMetrics
        val panelWidth = (screenWidth.takeIf { it > 0 } ?: fallback.widthPixels) / 3
        val panelHeight = (screenHeight.takeIf { it > 0 } ?: fallback.heightPixels) / 3
        return FrameLayout.LayoutParams(panelWidth, panelHeight, Gravity.TOP or Gravity.START)
    }

    private fun appendLog(message: String) {
        val now = System.currentTimeMillis()
        appendFileLog(message, now)
        if (debugLogsEnabled && !menuOpen) appendScreenLog(now, message)
    }

    private fun appendScreenLog(timestampMillis: Long, message: String) {
        logLines.add(timestampMillis, formattedLogLine(message, timestampMillis))
        if (!logRenderScheduled) {
            logRenderScheduled = true
            mainHandler.postDelayed(renderLogLines, LOG_RENDER_INTERVAL_MILLIS)
        }
    }

    private fun appendFileLog(message: String, timestampMillis: Long) {
        logLinesSinceStats++
        sessionLog?.appendTimestamped(message, timestampMillis)
        synchronized(recentSessionMessages) {
            recentSessionMessages.addLast(message)
            while (recentSessionMessages.size > RECENT_SESSION_MESSAGE_LIMIT) {
                recentSessionMessages.removeFirst()
            }
        }
    }

    private fun formattedLogLine(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        val activeLog = SessionLogFile(openSessionLogSink())
        sessionLogDestination = activeLog.destination
        runCatching {
            activeLog.startSession(
                "nevoplay log started " +
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                    "pid=${Process.myPid()} build=${BuildConfig.BUILD_ID} " +
                    // The commit alone is ambiguous: a build from a dirty tree reports the commit it
                    // was based on, so a log can claim the previous version - which happened, and cost
                    // a round of "which build produced this?". The version name cannot lie.
                    "version=${BuildConfig.APP_VERSION} " +
                    "path=${activeLog.destination}",
            )
        }
        sessionLog = activeLog
        if (logcatTap == null) {
            logcatTap = LogcatTap(
                append = { message -> runCatching { sessionLog?.append(message) } },
                isAlreadyLogged = { message ->
                    synchronized(recentSessionMessages) { message in recentSessionMessages }
                },
            ).also { it.start() }
        }
    }

    /**
     * Prefers the shared Downloads collection: from Android 11 on, the app-private
     * `Android/data/<package>` tree is hidden from file managers and MTP, which makes the
     * log impossible to collect on a head unit. Falls back to app storage when the media
     * store is missing (Android 9) or refuses the write.
     */
    private fun openSessionLogSink(): SessionLogSink {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val shared = MediaStoreSessionLogSink(this)
            if (runCatching { shared.openTruncating().close() }.isSuccess) return shared
            Log.w(TAG, "Shared log destination is unavailable; using app storage instead")
        }
        val baseDirectory = getExternalFilesDir(null) ?: filesDir
        return FileSessionLogSink(File(File(baseDirectory, "logs"), "nevoplay.log"))
    }

    private fun refreshLogView(nowMillis: Long) {
        if (!debugLogsEnabled || menuOpen) return
        val cutoff = nowMillis - LOG_RETENTION_MILLIS
        logLines.expireBefore(cutoff)
        statusView?.text = logLines.renderedText()
        scrollLogsToBottom()

        mainHandler.removeCallbacks(expireOldLogLines)
        logLines.firstTimestampMillis?.let { oldest ->
            val delay = (oldest + LOG_RETENTION_MILLIS - nowMillis + 1L)
                .coerceAtLeast(1L)
            mainHandler.postDelayed(expireOldLogLines, delay)
        }
    }

    private fun clearScreenLogs() {
        synchronized(pendingScreenLogsLock) {
            pendingScreenLogs.clear()
            screenDrainPosted = false
        }
        mainHandler.removeCallbacks(drainScreenLogs)
        logLines.clear()
        mainHandler.removeCallbacks(expireOldLogLines)
        mainHandler.removeCallbacks(renderLogLines)
        logRenderScheduled = false
        statusView?.text = ""
    }

    private fun scrollLogsToBottom() {
        statusScrollView?.post {
            statusScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    /**
     * Watches the real window insets instead of inferring the bar state from configuration changes,
     * and attaches to the app's own root view - never to the decor view. On Android 11 the decor's
     * own onApplyWindowInsets is what turns the bars into content padding, so replacing that
     * listener would silently break the layout the app needs to react to; returning the insets
     * unchanged keeps the dispatcher's behaviour intact.
     */
    private fun installSystemBarObserver(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            onSystemInsetsChanged(insets)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun onSystemInsetsChanged(insets: WindowInsetsCompat) {
        val statusBarVisible = insets.isVisible(WindowInsetsCompat.Type.statusBars())
        val navigationBarVisible = insets.isVisible(WindowInsetsCompat.Type.navigationBars())
        val overridden = (hideTopBar && statusBarVisible) || (hideBottomBar && navigationBarVisible)
        val now = android.os.SystemClock.uptimeMillis()
        var layoutChanged = false
        if (!overridden) {
            // The bars are as requested (or nothing has to be hidden): the vehicle is not holding them.
            if (vehicleEnforcesSystemBars) {
                vehicleEnforcesSystemBars = false
                layoutChanged = true
                appendLog("System bars are ours again; going back to fullscreen")
            }
            systemBarOverrideStrikes = 0
        } else if (
            lastFullscreenRequestUptime != 0L &&
            now - lastFullscreenRequestUptime <= SYSTEM_BAR_OVERRIDE_WINDOW_MILLIS
        ) {
            // We asked for a hidden bar a moment ago and it is visible anyway: the vehicle forced it
            // back. Stop re-requesting fullscreen - that fight is what made the window flicker - and
            // inset the window instead, so CarPlay re-handshakes at the size the bar leaves free.
            systemBarOverrideStrikes++
            if (systemBarOverrideStrikes >= SYSTEM_BAR_OVERRIDE_STRIKES && !vehicleEnforcesSystemBars) {
                vehicleEnforcesSystemBars = true
                layoutChanged = true
                appendLog(
                    "The vehicle is enforcing its own system bars; keeping fullscreen off and " +
                        "using the space they leave",
                )
            }
        }
        if (layoutChanged) {
            // Posted: applyFullscreenMode() changes the bars, which dispatches insets again.
            mainHandler.post { applyFullscreenMode() }
        }
        // However the bars got here, the space they leave is the size CarPlay should run at.
        // Re-applied on every dispatch rather than only when the number moves: the vehicle's latch
        // flips without changing the inset, and the answer depends on that latch as well.
        statusBarTopInsetPx = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
        mainHandler.post {
            applyBarInsets()
            updateIdleTitleSize()
        }
        refreshDisplaySizeAfterLayout()
    }

    /**
     * Re-asserts fullscreen from lifecycle events that the bars themselves did not cause. It is
     * skipped while the vehicle owns the bars, and while the window is oscillating: in both cases
     * the request comes straight back, which is the flicker the user sees. The Settings switches
     * reset both latches, so an explicit choice still wins.
     *
     * Each re-assert is counted and logged at most once a second, because whether this path is what
     * drives a fight is exactly what the device log has to show.
     */
    private fun reassertFullscreenIfNeeded() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now < displayYieldUntilUptime) {
            displayYieldSkips++
            return
        }
        if (vehicleEnforcesSystemBars) return
        if (displayYieldSkips > 0) {
            appendLog("Fullscreen requests resumed after yielding ($displayYieldSkips suppressed)")
            displayYieldSkips = 0
        }
        fullscreenReassertCount++
        if (now - lastReassertLogUptime >= FULLSCREEN_REASSERT_LOG_INTERVAL_MILLIS) {
            lastReassertLogUptime = now
            appendLog(
                "Re-asserted fullscreen (#$fullscreenReassertCount) " +
                    "viewport=${videoView?.width}x${videoView?.height}",
            )
        }
        applyFullscreenMode()
    }

    private fun resetVehicleSystemBarLatch() {
        vehicleEnforcesSystemBars = false
        systemBarOverrideStrikes = 0
        lastFullscreenRequestUptime = 0L
        displayRevertStrikes = 0
        displayYieldUntilUptime = 0L
        lastDisplayRevertUptime = 0L
    }

    // statusBarColor and navigationBarColor are deprecated as of Android 15, where the system draws
    // the bars itself; on the older head units this targets they are still the only way to colour them.
    @Suppress("DEPRECATION")
    private fun applyFullscreenMode() {
        val hideTop = hideTopBar
        val hideBottom = hideBottomBar
        // The window behind the surface follows the theme: it used to be black so a light theme
        // could not flash through while the bars animate, and the palette is that same near-black
        // in dark mode. In light mode the app really is light, and the window has to be too.
        applySystemBarPalette()
        // Only a pair of bars we really control gets the edge-to-edge layout. While the vehicle owns
        // a bar the content has to be inset by it, which is what shrinks the video and makes the
        // CarPlay handshake pick the smaller resolution instead of hiding content behind the bar.
        val edgeToEdge = hideTop && hideBottom && !vehicleEnforcesSystemBars
        WindowCompat.setDecorFitsSystemWindows(window, !edgeToEdge)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        var requestedHide = false
        if (hideTop) {
            if (!vehicleEnforcesSystemBars) {
                controller.hide(WindowInsetsCompat.Type.statusBars())
                requestedHide = true
            }
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
        if (hideBottom) {
            if (!vehicleEnforcesSystemBars) {
                controller.hide(WindowInsetsCompat.Type.navigationBars())
                requestedHide = true
            }
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (requestedHide) {
            lastFullscreenRequestUptime = android.os.SystemClock.uptimeMillis()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun CarPlayStatus.describe(): String = when (this) {
        CarPlayStatus.DiscoveringMfi -> "Preparing MFi authentication"
        CarPlayStatus.WaitingForMfi -> "Waiting for MFi coprocessor"
        CarPlayStatus.RequestingMfiPermission -> "Requesting MFi USB permission"
        CarPlayStatus.MfiReady -> "MFi authentication ready"
        CarPlayStatus.StartingHotspot -> "Starting wireless hotspot"
        is CarPlayStatus.HotspotReady ->
            "Hotspot ready: $backend, $ssid, $band, " +
                "channel ${if (channel == 0) "auto" else channel}"
        CarPlayStatus.WaitingForPairedIphone -> "Waiting for paired iPhone"
        CarPlayStatus.ConnectingBluetooth -> "Connecting Bluetooth"
        CarPlayStatus.RunningWireless -> "Wireless CarPlay control running"
        CarPlayStatus.WirelessActive -> "Wireless CarPlay active"
        CarPlayStatus.DiscoveringIphone -> "Discovering iPhone"
        CarPlayStatus.WaitingForIphone -> "Waiting for iPhone over USB"
        CarPlayStatus.RequestingIphonePermission -> "Requesting iPhone USB permission"
        CarPlayStatus.WaitingForReenumeration -> "Waiting for iPhone re-enumeration"
        CarPlayStatus.SelectingConfiguration -> "Selecting CarPlay configuration"
        CarPlayStatus.OpeningDataPaths -> "Opening USB data paths"
        CarPlayStatus.Pairing -> "Pairing with iPhone"
        CarPlayStatus.ConnectingControl -> "Connecting iAP2 control"
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) "Starting AirPlay service" else "Attaching NCM/AirPlay network"
        CarPlayStatus.RunningControl -> "CarPlay control running"
        CarPlayStatus.ControlEnded -> "CarPlay control window ended"
        is CarPlayStatus.Failed -> "Failed: ${message}"
    }

    private companion object {
        const val TAG = "nevoplay-usb"
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        const val LOG_RETENTION_MILLIS = 5 * 60_000L
        const val LOG_RENDER_INTERVAL_MILLIS = 100L
        const val MAX_SCREEN_LOG_LINES = 100
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val SYSTEM_BAR_OVERRIDE_WINDOW_MILLIS = 1_500L
        const val SYSTEM_BAR_OVERRIDE_STRIKES = 2

        /** A burst of undone resizes means the vehicle is toggling its bars; stop touching them. */
        const val DISPLAY_REVERT_STRIKE_WINDOW_MILLIS = 2_000L
        const val DISPLAY_REVERT_STRIKES = 4
        const val DISPLAY_YIELD_MILLIS = 60_000L
        const val FULLSCREEN_REASSERT_LOG_INTERVAL_MILLIS = 1_000L
        /** Consecutive Wi-Fi P2P failures before the driver is told to help. */
        const val HOTSPOT_TROUBLE_AFTER_FAILURES = 4
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val AUDIO_CAPTURE_MARKER = "audio-capture.enabled"
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        const val PROTOCOL_TRACE_PREFIX = "TRACE "
        const val THREE_FINGER_TAP_COUNT = 3
        const val THREE_FINGER_TAPS_TO_SETTINGS = 2
        const val THREE_FINGER_TAP_SLOP_DP = 20
        const val THREE_FINGER_TAP_MAX_MILLIS = 350L

        /** Gap allowed between the two taps; three fingers cannot be placed as fast as one. */
        const val THREE_FINGER_DOUBLE_TAP_WINDOW_MILLIS = 700L
        const val IDLE_PANEL_SIDE_BY_SIDE_PX = 1400
        const val PANEL_EDGE_FRACTION = 0.042f
        const val IDLE_TITLE_TEXT_SP = 48f
        const val IDLE_TITLE_MIN_LINES = 2
        const val IDLE_TITLE_MAX_LINES = 3
        const val IDLE_TITLE_HEIGHT_FRACTION = 0.055f
        const val IDLE_TITLE_WIDTH_FRACTION = 0.06f
        const val MAX_SETTINGS_MENU_WIDTH_PX = 1200

        /** The settings column: about a third of a head unit's width, capped in pixels. */
        const val SETTINGS_PANEL_WIDTH_FRACTION = 0.37f
        const val MAX_SETTINGS_PANEL_WIDTH_PX = 1000

        /** Below this width, or in portrait, there is no room for a column beside the picture. */
        const val RIGHT_COLUMN_SETTINGS_MIN_WIDTH_PX = 1600

        /** Padding inside the panel, measured against the panel and not against the screen. */
        const val SETTINGS_PANEL_EDGE_FRACTION = 0.04f
        const val MAX_SETTINGS_PANEL_EDGE_DP = 40
        // Read through the palette on every access: the dark and light sets are swapped at runtime,
        // and a `val` here would freeze whichever one was active when the class loaded.
        val MENU_BACKGROUND: Int get() = HostUi.BG
        val MENU_SECONDARY: Int get() = HostUi.DIM
        val MENU_LABEL: Int get() = HostUi.TEXT
        val MENU_FAINT: Int get() = HostUi.FAINT
        val MENU_ACCENT: Int get() = HostUi.ACCENT
        val MENU_ACCENT_TRACK: Int get() = HostUi.ACCENT_DIM
        val MENU_TRACK_OFF: Int get() = HostUi.LINE_2
        val MENU_BUTTON_TEXT: Int get() = HostUi.BG
        val MENU_DANGER: Int get() = HostUi.ERROR
        val NO_VIDEO_BACKGROUND: Int get() = HostUi.BG
    }

    private data class DisplaySize(val width: Int, val height: Int)
    private data class PendingLog(
        val generation: Int,
        val timestampMillis: Long,
        val message: String,
    )
    private data class HotspotStatus(
        val state: String,
        val ssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val backend: String? = null,
        val address: String? = null,
    )
}

/** Process-local hand-off for keeping the CarPlay session alive while no Activity is visible. */
private object CarPlayBackgroundSession {
    data class Snapshot(
        val controller: CarPlayController,
        val sink: AndroidMediaSink,
        val width: Int,
        val height: Int,
    )

    private var controller: CarPlayController? = null
    private var sink: AndroidMediaSink? = null
    private var width = 0
    private var height = 0

    @Synchronized
    fun snapshot(): Snapshot? {
        val currentController = controller ?: return null
        val currentSink = sink ?: return null
        return Snapshot(currentController, currentSink, width, height)
    }

    @Synchronized
    fun store(controller: CarPlayController, sink: AndroidMediaSink, width: Int, height: Int) {
        this.controller = controller
        this.sink = sink
        this.width = width
        this.height = height
    }

    @Synchronized
    fun clear(expected: CarPlayController? = null) {
        if (expected != null && controller !== expected) return
        controller = null
        sink = null
        width = 0
        height = 0
    }
}
