package ai.spatialwalk.scenes.rtc

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Network availability checks. */
object NetworkStatus {

    /** Whether there is a validated internet connection right now. VALIDATED rules out
     * "connected to Wi-Fi but with no route out". */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Suspend until the network comes back. Returns immediately if already online;
     * unregisters the callback when the coroutine is cancelled, so nothing leaks. */
    suspend fun awaitOnline(context: Context) {
        if (isOnline(context)) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        suspendCancellableCoroutine { cont ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // onAvailable does not mean validated, so check once more.
                    if (cont.isActive && isOnline(context)) {
                        runCatching { cm.unregisterNetworkCallback(this) }
                        cont.resume(Unit)
                    }
                }
            }
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
            cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(callback) } }
        }
    }
}
