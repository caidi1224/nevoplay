package com.shilapi.xcertplay

import android.content.Context
import android.util.Log
import com.changan.sda.opensdk.client.CaOpenSdkManager
import com.changan.sda.opensdk.client.OpenSdkInitCallback
import com.changan.sda.opensdk.client.consts.VehicleAreaType
import com.changan.sda.opensdk.client.consts.VehiclePropertyIds
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional bridge to the Changan Qiyuan OpenSDK, the vehicle-signal service of a Qiyuan head unit.
 *
 * The SDK is an AIDL client: it binds a Changan system service, identifies the caller by package
 * name, signature and the clientId/clientSecret pair that the app declares as manifest meta-data,
 * and only then answers property requests. Everything here is therefore best-effort - a build with
 * no credentials (or a head unit without that service, or a non-Changan device) fails to initialise
 * and the app simply carries on with its own sources of truth. Nothing in the CarPlay path depends
 * on this class.
 *
 * This probe exists to answer one question from the device log: does the service authorise this app
 * at all? It reports the init outcome and one property read; gear-driven behaviour can only be built
 * once that is known to work.
 */
internal object QiyuanVehicleSignals {
    private const val TAG = "xcertplay-qiyuan"

    private val started = AtomicBoolean(false)

    /** Runs at most once per process. [log] must be safe to call from any thread. */
    fun probeOnce(context: Context, log: (String) -> Unit) {
        if (!started.compareAndSet(false, true)) return
        try {
            val manager = CaOpenSdkManager.getInstance()
            manager.registerInitCallback(
                object : OpenSdkInitCallback {
                    override fun onInitSuccess() {
                        log("Qiyuan OpenSDK authorised this app")
                        readGear(manager, log)
                    }

                    override fun onInitError(message: String?) {
                        log(
                            "Qiyuan OpenSDK refused to initialise: " +
                                (message ?: "no reason reported"),
                        )
                    }
                },
            )
            manager.init(context)
        } catch (error: Throwable) {
            log("Qiyuan OpenSDK is unusable here: ${error.javaClass.simpleName}: ${error.message}")
            Log.w(TAG, "probe failed", error)
        }
    }

    /**
     * Reads the standard Car API gear selector position. A successful read proves the whole chain
     * (bind, authorise, property access), which is what the flicker work would need to pre-empt the
     * vehicle's status bar as soon as the car is put into gear.
     */
    private fun readGear(manager: CaOpenSdkManager, log: (String) -> Unit) {
        try {
            val result = manager.getProperty(
                Integer::class.java,
                VehiclePropertyIds.GEAR_SELECTION,
                VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL,
            )
            if (result == null) {
                log("Qiyuan gear read returned no result")
                return
            }
            log("Qiyuan gear read: code=${result.code} msg=${result.msg} data=${result.data}")
        } catch (error: Throwable) {
            log("Qiyuan gear read failed: ${error.javaClass.simpleName}: ${error.message}")
            Log.w(TAG, "gear read failed", error)
        }
    }
}
