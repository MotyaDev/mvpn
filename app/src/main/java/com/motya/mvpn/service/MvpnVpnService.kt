package com.motya.mvpn.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.motya.mvpn.MainActivity
import com.motya.mvpn.R
import com.motya.mvpn.core.ConnectionState
import com.motya.mvpn.core.VpnState
import com.motya.mvpn.core.VpnStatus
import com.motya.mvpn.core.XrayConfigFactory
import com.motya.mvpn.core.XrayCore
import com.motya.mvpn.data.VlessParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MvpnVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var statsJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            ACTION_START -> startTunnel(intent.getStringExtra(EXTRA_VLESS).orEmpty())
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun startTunnel(raw: String) {
        if (prepare(this) != null) {
            VpnStatus.update(VpnState(ConnectionState.Error, "Android ещё не дал VPN-разрешение"))
            stopSelf()
            return
        }

        VpnStatus.update(VpnState(ConnectionState.Connecting, "Поднимаю Xray TUN…"))
        runCatching {
            val profile = VlessParser.parse(raw)
            val config = XrayConfigFactory.build(profile)
            XrayCore.init(this)

            tun?.close()
            val builder = Builder()
                .setSession("MVPN")
                .setMtu(1500)
                .addAddress("172.19.0.1", 30)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)
                .allowFamily(android.system.OsConstants.AF_INET)
                .setBlocking(false)
            runCatching { builder.addDisallowedApplication(packageName) }
            tun = builder.establish() ?: error("Не удалось создать TUN-интерфейс")

            startForeground(NOTIFICATION_ID, notification("MVPN подключается", profile.name))
            XrayCore.start(config, tun!!.fd)
            VpnStatus.update(VpnState(ConnectionState.Connected, "Подключено к ${profile.name}"))
            statsJob?.cancel()
            statsJob = scope.launch {
                var upTotal = 0L
                var downTotal = 0L
                while (XrayCore.isRunning) {
                    delay(1000)
                    val (up, down) = XrayCore.traffic()
                    upTotal += up
                    downTotal += down
                    VpnStatus.update(VpnState(ConnectionState.Connected, "Туннель мурчит. ${XrayCore.version()}", upTotal, downTotal))
                }
            }
        }.onFailure { error ->
            VpnStatus.update(VpnState(ConnectionState.Error, error.message ?: error.javaClass.simpleName))
            stopTunnel()
        }
    }

    private fun stopTunnel() {
        statsJob?.cancel()
        statsJob = null
        runCatching { XrayCore.stop() }
        runCatching { tun?.close() }
        tun = null
        VpnStatus.update(VpnState(ConnectionState.Disconnected, "Отключено. Капибара отдыхает"))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(title: String, text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        const val ACTION_START = "com.motya.mvpn.START"
        const val ACTION_STOP = "com.motya.mvpn.STOP"
        const val EXTRA_VLESS = "vless"
        private const val CHANNEL_ID = "mvpn"
        private const val NOTIFICATION_ID = 79

        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "MVPN", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }
}
