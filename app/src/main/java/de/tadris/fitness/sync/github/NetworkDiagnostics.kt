package de.tadris.fitness.sync.github

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.InetAddress

/** Reports what this app sees of the network: link, DNS, and a few HTTPS fetches. Run off the main thread. */
object NetworkDiagnostics {

    private val hosts = listOf("api.github.com", "github.com", "example.com", "duckduckgo.com", "google.com")

    fun run(context: Context): String {
        val out = StringBuilder()
        out.appendLine("== Network as the app sees it ==")
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork
            if (net == null) {
                out.appendLine("No active network")
            } else {
                val caps = cm.getNetworkCapabilities(net)
                val transports = listOf(
                    NetworkCapabilities.TRANSPORT_WIFI to "wifi", NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
                    NetworkCapabilities.TRANSPORT_VPN to "VPN", NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
                ).filter { caps?.hasTransport(it.first) == true }.joinToString { it.second }
                out.appendLine("Transport: $transports")
                out.appendLine("Internet: ${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}, validated: ${caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}")
                cm.getLinkProperties(net)?.let { lp ->
                    out.appendLine("Addresses: ${lp.linkAddresses.joinToString { it.address.hostAddress ?: "?" }}")
                    out.appendLine("DNS servers: ${lp.dnsServers.joinToString { it.hostAddress ?: "?" }}")
                    out.appendLine("Private DNS: active=${lp.isPrivateDnsActive} name=${lp.privateDnsServerName}")
                    if (Build.VERSION.SDK_INT >= 30) out.appendLine("NAT64 prefix: ${lp.nat64Prefix}")
                }
            }
        } catch (e: Exception) {
            out.appendLine("Cannot read network state: ${e.javaClass.simpleName}")
        }
        out.appendLine()
        out.appendLine("== DNS and HTTPS ==")
        for (h in hosts) {
            val dns = try {
                InetAddress.getAllByName(h).joinToString { it.hostAddress ?: "?" }
            } catch (e: Exception) {
                "FAILED (${e.javaClass.simpleName})"
            }
            val http = try {
                "HTTP ${GitHubHttp.request("GET", "https://$h/").code}"
            } catch (e: Exception) {
                "FAILED (${e.javaClass.simpleName}: ${e.message?.take(60)})"
            }
            out.appendLine("$h\n  DNS: $dns\n  HTTPS: $http")
        }
        out.appendLine()
        out.appendLine("== Without system DNS ==")
        out.appendLine("DoH via 1.1.1.1, github.com: " + try {
            val r = GitHubHttp.request("GET", "https://1.1.1.1/dns-query?name=github.com&type=A", accept = "application/dns-json")
            if (r.ok) r.text.take(200) else "HTTP ${r.code}"
        } catch (e: Exception) {
            "FAILED (${e.javaClass.simpleName}: ${e.message?.take(60)})"
        })
        return out.toString()
    }
}
