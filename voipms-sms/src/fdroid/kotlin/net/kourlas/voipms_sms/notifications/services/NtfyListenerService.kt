/*
 * VoIP.ms SMS
 * Copyright (C) 2017-2021 Michael Kourlas
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.kourlas.voipms_sms.notifications.services

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.kourlas.voipms_sms.R
import net.kourlas.voipms_sms.conversations.ConversationsActivity
import net.kourlas.voipms_sms.notifications.Notifications
import net.kourlas.voipms_sms.preferences.getNtfyTopic
import net.kourlas.voipms_sms.preferences.getNtfyPersistentConnection
import net.kourlas.voipms_sms.sms.workers.SyncWorker
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * Service that maintains a WebSocket connection to ntfy.sh for real-time notifications.
 * When a notification is received, it triggers a sync with VoIP.ms to fetch new messages.
 */
class NtfyListenerService : Service() {
    
    private val binder = NtfyBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var reconnectJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectionAttempts = 0
    
    private val okHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    
    private val moshi = Moshi.Builder().build()
    
    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ntfy_listener_channel"
        private const val RECONNECT_DELAY_MS = 5000L
        private const val MAX_RECONNECT_DELAY_MS = 60000L
        
        fun startService(context: Context) {
            val intent = Intent(context, NtfyListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, NtfyListenerService::class.java)
            context.stopService(intent)
        }
    }
    
    inner class NtfyBinder : Binder() {
        fun getService(): NtfyListenerService = this@NtfyListenerService
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only start as foreground service if persistent connection is enabled
        if (getNtfyPersistentConnection(this)) {
            startForeground(NOTIFICATION_ID, createNotification())
            serviceScope.launch {
                connectWebSocket()
            }
        } else {
            // For non-persistent mode, just start WebSocket briefly and stop
            serviceScope.launch {
                connectWebSocket()
                // Stop service after a short delay if not persistent
                delay(30000) // 30 seconds
                if (!getNtfyPersistentConnection(this@NtfyListenerService)) {
                    stopSelf()
                }
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onDestroy() {
        super.onDestroy()
        disconnectWebSocket()
        releaseWakeLock()
        serviceScope.cancel()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ntfy Listener",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Maintains connection to ntfy.sh for push notifications"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val intent = Intent(this, ConversationsActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VoIP.ms SMS")
            .setContentText(if (isConnected) "Connected to ntfy.sh" else "Connecting to ntfy.sh...")
            .setSmallIcon(R.drawable.ic_message_sync_toolbar_24dp)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }
    
    private fun updateNotification() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }
    
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VoIPmsSMS::NtfyListenerService"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
    }
    
    private suspend fun connectWebSocket() {
        val topic = getNtfyTopic(this)
        if (topic.isBlank()) {
            return
        }
        
        // Prevent duplicate connections
        if (webSocket != null && isConnected) {
            return
        }
        
        // Close any existing connection first
        webSocket?.close(1000, "Reconnecting")
        
        val request = Request.Builder()
            .url("wss://ntfy.sh/$topic/ws")
            .build()
        
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                connectionAttempts = 0 // Reset on successful connection
                updateNotification()
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleNtfyMessage(text)
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                updateNotification()
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                updateNotification()
                scheduleReconnect()
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                updateNotification()
                scheduleReconnect()
            }
        })
    }
    
    private fun handleNtfyMessage(message: String) {
        try {
            val adapter = moshi.adapter(NtfyMessage::class.java)
            val ntfyMessage = adapter.fromJson(message)
            
            // Only sync on actual message events, not keepalive or open events
            val event = ntfyMessage?.event
            if (event != null && event != "message") {
                // Ignore keepalive, open, and other non-message events
                return
            }
            
            // Trigger sync with VoIP.ms to fetch new messages
            if (Notifications.getInstance(this).getNotificationsEnabled()) {
                SyncWorker.performPartialSynchronization(this)
            }
        } catch (e: Exception) {
            // On parsing errors, don't sync to avoid false positives
            // Log the error for debugging
        }
    }
    
    private fun scheduleReconnect() {
        if (!getNtfyPersistentConnection(this)) {
            return
        }
        
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            connectionAttempts++
            
            // Exponential backoff: 5s, 10s, 20s, 40s, 60s (max)
            val delay = minOf(
                RECONNECT_DELAY_MS * (1 shl (connectionAttempts - 1)),
                MAX_RECONNECT_DELAY_MS
            )
            
            delay(delay)
            
            if (isActive && getNtfyPersistentConnection(this@NtfyListenerService)) {
                connectWebSocket()
            }
        }
    }
    
    private fun disconnectWebSocket() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "Service stopping")
        webSocket = null
        isConnected = false
    }
    
    fun restartConnection() {
        serviceScope.launch {
            disconnectWebSocket()
            if (getNtfyPersistentConnection(this@NtfyListenerService)) {
                connectWebSocket()
            }
        }
    }
    
    fun isWebSocketConnected(): Boolean = isConnected
}

@JsonClass(generateAdapter = true)
data class NtfyMessage(
    val id: String?,
    val time: Long?,
    val event: String?,
    val topic: String?,
    val message: String?,
    val title: String?,
    val priority: Int?,
    val tags: List<String>?,
    val click: String?,
    val actions: List<NtfyAction>?,
    val attachment: NtfyAttachment?
)

@JsonClass(generateAdapter = true)
data class NtfyAction(
    val id: String?,
    val action: String?,
    val label: String?,
    val clear: Boolean?,
    val url: String?,
    val method: String?,
    val headers: Map<String, String>?,
    val body: String?
)

@JsonClass(generateAdapter = true)
data class NtfyAttachment(
    val name: String?,
    val type: String?,
    val size: Long?,
    val expires: Long?,
    val url: String?
)
