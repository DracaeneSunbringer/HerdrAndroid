package dev.herdr.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.herdr.android.MainActivity
import dev.herdr.android.R
import dev.herdr.android.protocol.TerminalSession
import dev.herdr.android.protocol.TerminalSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class KeepAliveNotificationStatus(
    val active: Boolean = false,
    val title: String = "Herdr 远端连接保活中",
    val subtitle: String = "SSH 与终端流后台常驻运行中"
)

/**
 * Process-level holder so that active SSH/ZeroTier connections and attached terminal sessions
 * survive Activity recreation and backgrounding without interruption.
 */
object HerdrKeepAliveCoordinator {
    val processScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(KeepAliveNotificationStatus())
    val status: StateFlow<KeepAliveNotificationStatus> = _status.asStateFlow()

    val activeSession = MutableStateFlow<TerminalSession?>(null)
    val activeTerminalSummary = MutableStateFlow<TerminalSummary?>(null)

    @Volatile
    var userWantsConnectionAlive: Boolean = false

    @Volatile
    var isReconnectingInBackground: Boolean = false

    @Volatile
    var onDisconnectRequested: (() -> Unit)? = null

    fun updateStatus( context: Context, active: Boolean, title: String, subtitle: String) {
        val next = KeepAliveNotificationStatus(active = active, title = title, subtitle = subtitle)
        val prev = _status.value
        _status.value = next
        if (active) {
            if (!prev.active || prev.title != next.title || prev.subtitle != next.subtitle) {
                HerdrKeepAliveService.startOrUpdate(context.applicationContext)
            }
        } else if (prev.active) {
            HerdrKeepAliveService.stop(context.applicationContext)
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

class HerdrKeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var statusCollectorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        acquireLocks()
        statusCollectorJob = HerdrKeepAliveCoordinator.processScope.launch(Dispatchers.Main.immediate) {
            HerdrKeepAliveCoordinator.status.collect { current ->
                if (current.active) {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.notify(NOTIFICATION_ID, buildNotification(current))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_DISCONNECT_FROM_NOTIFICATION) {
            HerdrKeepAliveCoordinator.onDisconnectRequested?.invoke()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        acquireLocks()
        val currentStatus = HerdrKeepAliveCoordinator.status.value
        val notification = buildNotification(currentStatus)
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
            )
        }
        return START_STICKY
    }

    override fun onDestroy() {
        statusCollectorJob?.cancel()
        statusCollectorJob = null
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = nm.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Herdr 远端连接后台保活",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "保持 SSH、内置 ZeroTier 与远端终端流在后台及锁屏时不断线"
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(status: KeepAliveNotificationStatus): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            100,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, HerdrKeepAliveService::class.java).apply {
            action = ACTION_DISCONNECT_FROM_NOTIFICATION
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this,
            101,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_herdr_launcher)
            .setContentTitle(status.title)
            .setContentText(status.subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openPendingIntent)
            .addAction(0, "断开连接", disconnectPendingIntent)
            .build()
    }

    private fun acquireLocks() {
        runCatching {
            if (wakeLock == null) {
                val pm = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "HerdrAndroid::KeepAliveWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                // Hold wake lock while session is active so CPU/timers do not freeze in background
                wakeLock?.acquire(12 * 60 * 60 * 1000L)
            }
        }
        runCatching {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm?.createWifiLock(lockMode, "HerdrAndroid::KeepAliveWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        runCatching {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        }
    }

    private fun stopForegroundCompat() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        releaseLocks()
    }

    companion object {
        private const val CHANNEL_ID = "herdr_keep_alive_channel"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP_SERVICE = "dev.herdr.android.action.STOP_KEEP_ALIVE"
        private const val ACTION_DISCONNECT_FROM_NOTIFICATION = "dev.herdr.android.action.DISCONNECT_KEEP_ALIVE"

        fun startOrUpdate(context: Context) {
            runCatching {
                val intent = Intent(context, HerdrKeepAliveService::class.java)
                ContextCompat.startForegroundService(context, intent)
            }
        }

        fun stop(context: Context) {
            runCatching {
                val intent = Intent(context, HerdrKeepAliveService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                context.stopService(intent)
            }
        }
    }
}
