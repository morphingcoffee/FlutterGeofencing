// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.geofencing

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.util.Log
import androidx.core.app.JobIntentService
import com.google.android.gms.location.GeofencingEvent
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor.DartCallback
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.PluginRegistrantCallback
import io.flutter.view.FlutterCallbackInformation
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class GeofencingService : MethodCallHandler, JobIntentService() {
    private val queue = ArrayDeque<List<Any>>()
    private lateinit var mBackgroundChannel: MethodChannel
    private lateinit var mContext: Context

    companion object {
        @JvmStatic
        private val TAG = "GeofencingService"
        @JvmStatic
        private val JOB_ID = UUID.randomUUID().mostSignificantBits.toInt()
        @JvmStatic
        private var sBackgroundFlutterEngine: FlutterEngine? = null
        @JvmStatic
        private val sServiceStarted = AtomicBoolean(false)

        @JvmStatic
        private lateinit var sPluginRegistrantCallback: PluginRegistrantCallback

        @JvmStatic
        fun enqueueWork(context: Context, work: Intent) {
            enqueueWork(context, GeofencingService::class.java, JOB_ID, work)
        }

        @JvmStatic
        fun setPluginRegistrant(callback: PluginRegistrantCallback) {
            sPluginRegistrantCallback = callback
        }
    }

    private fun startGeofencingService(context: Context) {
        synchronized(sServiceStarted) {
            mContext = context
            if (sBackgroundFlutterEngine == null) {
                val callbackHandle = context.getSharedPreferences(
                        GeofencingPlugin.SHARED_PREFERENCES_KEY,
                        Context.MODE_PRIVATE)
                        .getLong(GeofencingPlugin.CALLBACK_DISPATCHER_HANDLE_KEY, 0)
                if (callbackHandle == 0L) {
                    Log.e(TAG, "Fatal: no callback registered")
                    return
                }

                val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)
                if (callbackInfo == null) {
                    Log.e(TAG, "Fatal: failed to find callback")
                    return
                }
                Log.i(TAG, "Starting GeofencingService...")
                sBackgroundFlutterEngine = FlutterEngine(context)

                val args = DartCallback(
                    context.assets,
                    FlutterInjector.instance().flutterLoader().findAppBundlePath(),
                    callbackInfo
                )
                sBackgroundFlutterEngine!!.dartExecutor.executeDartCallback(args)
                IsolateHolderService.setBackgroundFlutterEngine(sBackgroundFlutterEngine)
            }
        }
        mBackgroundChannel = MethodChannel(sBackgroundFlutterEngine!!.dartExecutor.binaryMessenger,
                "plugins.flutter.io/geofencing_plugin_background")
        mBackgroundChannel.setMethodCallHandler(this)
    }

   override fun onMethodCall(call: MethodCall, result: Result) {
       when(call.method) {
            "GeofencingService.initialized" -> {
                synchronized(sServiceStarted) {
                    while (!queue.isEmpty()) {
                        mBackgroundChannel.invokeMethod("", queue.remove())
                    }
                    sServiceStarted.set(true)
                }
            }
            "GeofencingService.promoteToForeground" -> {
                mContext.startForegroundService(Intent(mContext, IsolateHolderService::class.java))
            }
            "GeofencingService.demoteToBackground" -> {
                val intent = Intent(mContext, IsolateHolderService::class.java)
                intent.action = IsolateHolderService.ACTION_SHUTDOWN
                mContext.startForegroundService(intent)
            }
            else -> result.notImplemented()
        }
        result.success(null)
    }

    override fun onCreate() {
        super.onCreate()
        startGeofencingService(this)
    }

    override fun onHandleWork(intent: Intent) {
        val callbackHandle = intent.getLongExtra(GeofencingPlugin.CALLBACK_HANDLE_KEY, 0)
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent.hasError()) {
            Log.e(TAG, "Geofencing error: ${geofencingEvent.errorCode}")
            return
        }

        // Get the transition type.
        val geofenceTransition = geofencingEvent.geofenceTransition

        // Get the geofences that were triggered. A single event can trigger
        // multiple geofences.
        val triggeringGeofences = geofencingEvent.triggeringGeofences.map {
            it.requestId
        }

        val location = geofencingEvent.triggeringLocation
        val locationList = listOf(location.latitude,
                location.longitude)
        val geofenceUpdateList = listOf(callbackHandle,
                triggeringGeofences,
                locationList,
                geofenceTransition)

        synchronized(sServiceStarted) {
            if (!sServiceStarted.get()) {
                // Queue up geofencing events while background isolate is starting
                queue.add(geofenceUpdateList)
            } else {
                // Callback method name is intentionally left blank.
                Handler(mContext.mainLooper).post { mBackgroundChannel.invokeMethod("", geofenceUpdateList) }
            }
        }
    }
}
