package com.perecvpn.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.*
import java.io.File
import java.net.NetworkInterface

class PerecVpnService : VpnService(), PlatformInterface, CommandServerHandler {
    companion object {
        const val ACTION_START = "com.perecvpn.app.START"
        const val ACTION_STOP = "com.perecvpn.app.STOP"
        const val ACTION_STATUS = "com.perecvpn.app.STATUS"
        const val EXTRA_STATUS = "status"
        private const val CHANNEL = "perec_vpn"
        private const val NOTIFICATION_ID = 42
        private const val TAG = "PerecVpnService"
    }

    private var commandServer: CommandServer? = null
    private var tun: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        Libbox.setup(SetupOptions().apply {
            basePath = filesDir.absolutePath
            workingPath = (getExternalFilesDir(null) ?: filesDir).absolutePath
            tempPath = cacheDir.absolutePath
            debug = false
            logMaxLines = 1000
        })
        createChannel()
        startForeground(NOTIFICATION_ID, notification("ПЕРЕЦ VPN", "Запуск VPN…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            else -> startVpn()
        }
        return START_NOT_STICKY
    }

    private fun startVpn() {
        if (commandServer != null) return
        Thread {
            try {
                val configFile = File(filesDir, "active-config.json")
                if (!configFile.exists()) error("Конфигурация не найдена")
                val server = CommandServer(this, this)
                commandServer = server
                server.start()
                server.startOrReloadService(configFile.readText(), OverrideOptions())
                broadcast("Подключено")
                updateNotification("ПЕРЕЦ VPN", "VPN подключён")
            } catch (e: Exception) {
                Log.e(TAG, "VPN start failed", e)
                broadcast("Ошибка: ${e.message ?: "не удалось подключиться"}")
                stopVpn()
            }
        }.start()
    }

    private fun stopVpn() {
        Thread {
            try { commandServer?.closeService() } catch (_: Exception) {}
            try { commandServer?.close() } catch (_: Exception) {}
            commandServer = null
            try { tun?.close() } catch (_: Exception) {}
            tun = null
            broadcast("Отключено")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
    }

    override fun onDestroy() {
        try { commandServer?.closeService() } catch (_: Exception) {}
        try { commandServer?.close() } catch (_: Exception) {}
        try { tun?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        val b = super.onBind(intent)
        return b
    }

    override fun serviceStop() { stopVpn() }
    override fun serviceReload() { startVpn() }
    override fun getSystemProxyStatus(): SystemProxyStatus? = null
    override fun setSystemProxyEnabled(isEnabled: Boolean) {}
    override fun writeDebugMessage(message: String?) { Log.d(TAG, message ?: "") }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun autoDetectInterfaceControl(fd: Int) { protect(fd) }
    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int, destinationAddress: String, destinationPort: Int): ConnectionOwner {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) error("connection owner unsupported")
        val cm = getSystemService(ConnectivityManager::class.java)
        val uid = cm.getConnectionOwnerUid(ipProtocol, java.net.InetSocketAddress(sourceAddress, sourcePort), java.net.InetSocketAddress(destinationAddress, destinationPort))
        if (uid < 0) error("connection owner not found")
        return ConnectionOwner().apply {
            userId = uid
            userName = packageManager.getPackagesForUid(uid)?.firstOrNull() ?: ""
            setAndroidPackageNames(StringArray(packageManager.getPackagesForUid(uid)?.toList()?.iterator() ?: emptyList<String>().iterator()))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val cm = getSystemService(ConnectivityManager::class.java)
        val network = cm.activeNetwork
        val lp = network?.let { cm.getLinkProperties(it) }
        listener.updateDefaultInterface(lp?.interfaceName ?: "", lp?.let { runCatching { NetworkInterface.getByName(it.interfaceName).index }.getOrDefault(-1) } ?: -1, false, false)
    }
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {}

    override fun getInterfaces(): NetworkInterfaceIterator {
        val list = mutableListOf<io.nekohasekai.libbox.NetworkInterface>()
        val cm = getSystemService(ConnectivityManager::class.java)
        for (network in cm.allNetworks) {
            val lp = cm.getLinkProperties(network) ?: continue
            val nc = cm.getNetworkCapabilities(network) ?: continue
            val ni = lp.interfaceName?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() } ?: continue
            list += io.nekohasekai.libbox.NetworkInterface().apply {
                name = ni.name
                index = ni.index
                dnsServer = StringArray(lp.dnsServers.mapNotNull { it.hostAddress }.iterator())
                type = when {
                    nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                mtu = runCatching { ni.mtu }.getOrDefault(1500)
                addresses = StringArray(ni.interfaceAddresses.map { "${it.address.hostAddress}/${it.networkPrefixLength}" }.iterator())
                flags = 0
                metered = !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        return object : NetworkInterfaceIterator {
            val it = list.iterator()
            override fun hasNext() = it.hasNext()
            override fun next() = it.next()
        }
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() {}
    override fun readWIFIState(): WIFIState? = null
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun systemCertificates(): StringIterator = StringArray(emptyList<String>().iterator())
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun registerMyInterface(name: String?) {}
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}

    override fun openTun(options: TunOptions): Int {
        if (VpnService.prepare(this) != null) error("VPN permission not granted")
        val b = Builder().setSession("ПЕРЕЦ VPN").setMtu(options.mtu)
        val a4 = options.inet4Address
        while (a4.hasNext()) { val x = a4.next(); b.addAddress(x.address(), x.prefix()) }
        val a6 = options.inet6Address
        while (a6.hasNext()) { val x = a6.next(); b.addAddress(x.address(), x.prefix()) }
        if (options.autoRoute) {
            b.addDnsServer(options.dnsServerAddress.value)
            if (Build.VERSION.SDK_INT >= 33) {
                val r4 = options.inet4RouteAddress
                if (r4.hasNext()) while (r4.hasNext()) { val x = r4.next(); b.addRoute(x.address(), x.prefix()) } else b.addRoute("0.0.0.0", 0)
                val r6 = options.inet6RouteAddress
                if (r6.hasNext()) while (r6.hasNext()) { val x = r6.next(); b.addRoute(x.address(), x.prefix()) } else b.addRoute("::", 0)
            } else {
                val r4 = options.inet4RouteRange
                while (r4.hasNext()) { val x = r4.next(); b.addRoute(x.address(), x.prefix()) }
                val r6 = options.inet6RouteRange
                while (r6.hasNext()) { val x = r6.next(); b.addRoute(x.address(), x.prefix()) }
            }
        }
        tun = b.establish() ?: error("Не удалось создать TUN")
        return tun!!.fd
    }

    override fun sendNotification(notification: io.nekohasekai.libbox.Notification) {
        updateNotification(notification.title, notification.body ?: "")
    }

    private fun broadcast(status: String) = sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, status))
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "ПЕРЕЦ VPN", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(title: String, text: String): Notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle(title).setContentText(text).setOngoing(true).build()
    private fun updateNotification(title: String, text: String) = getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, text))

    class StringArray(private val iterator: Iterator<String>) : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }
}
