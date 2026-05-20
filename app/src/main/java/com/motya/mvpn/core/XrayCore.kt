package com.motya.mvpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.OsConstants
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import libv2ray.ProcessFinder
import java.io.File
import java.net.InetSocketAddress
import com.motya.mvpn.data.VlessProfile

object XrayCore {
    private val controller: CoreController by lazy { Libv2ray.newCoreController(Callback()) }
    private var initialized = false

    val isRunning: Boolean get() = controller.isRunning

    fun init(context: Context) {
        if (initialized) return
        val assetDir = File(context.filesDir, "xray").apply { mkdirs() }
        listOf("geoip.dat", "geosite.dat", "geoip-only-cn-private.dat").forEach { name ->
            runCatching {
                val out = File(assetDir, name)
                if (!out.exists() || out.length() == 0L) {
                    context.assets.open(name).use { input -> out.outputStream().use(input::copyTo) }
                }
            }
        }
        Libv2ray.initCoreEnv(assetDir.absolutePath, "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            controller.registerProcessFinder(AndroidProcessFinder(context.applicationContext))
        }
        initialized = true
    }

    fun version(): String = runCatching { Libv2ray.checkVersionX() }.getOrDefault("Xray-core")

    fun start(config: String, tunFd: Int) {
        controller.startLoop(config, tunFd)
    }

    fun stop() {
        if (controller.isRunning) controller.stopLoop()
    }

    fun traffic(): Pair<Long, Long> = controller.queryStats("proxy", "uplink") to controller.queryStats("proxy", "downlink")

    fun measureOutboundDelay(context: Context, profile: VlessProfile): Long {
        init(context)
        return Libv2ray.measureOutboundDelay(XrayConfigFactory.buildPing(profile), "https://www.google.com/generate_204")
    }

    private class Callback : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(status: Long, message: String?): Long {
            if (!message.isNullOrBlank()) {
                VpnStatus.update(VpnState(ConnectionState.Connected, message))
            }
            return 0
        }
    }

    private class AndroidProcessFinder(context: Context) : ProcessFinder {
        private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || destIP.isBlank() || destPort == 0L) return -1
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1
            }
            return runCatching {
                connectivityManager.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt()),
                ).toLong()
            }.getOrDefault(-1)
        }
    }
}
