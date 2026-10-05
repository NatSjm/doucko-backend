package cz.doucko.practice

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * practice-flow "Feedback never comes from AI in MVP" (TC-6, FR-44): while a test class runs, every
 * outbound connection the JVM opens through `java.net` (plain sockets, `HttpURLConnection`, `HttpClient`)
 * is refused unless it goes to this machine or the local network (the test database container). The JDK
 * asks the default [ProxySelector] before every such connection; this one refuses and records the target.
 * After each test, a refused connection fails the test.
 */
class DenyOutboundNetwork :
    BeforeAllCallback,
    AfterAllCallback,
    AfterEachCallback {
    private var previous: ProxySelector? = null

    override fun beforeAll(context: ExtensionContext) {
        previous = ProxySelector.getDefault()
        ProxySelector.setDefault(Guard(previous))
    }

    override fun afterAll(context: ExtensionContext) {
        ProxySelector.setDefault(previous)
    }

    override fun afterEach(context: ExtensionContext) {
        val refused = denied.toList()
        denied.clear()
        check(refused.isEmpty()) { "outbound network connections were attempted: $refused" }
    }

    private class Guard(
        private val delegate: ProxySelector?,
    ) : ProxySelector() {
        override fun select(uri: URI): List<Proxy> {
            val host = uri.host ?: return delegate?.select(uri) ?: listOf(Proxy.NO_PROXY)
            if (!local(host)) {
                denied += "${uri.scheme}://$host:${uri.port}"
                throw IllegalArgumentException("outbound network denied (TC-6)")
            }
            return delegate?.select(uri) ?: listOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(
            uri: URI,
            sa: SocketAddress,
            ioe: IOException,
        ) {
            delegate?.connectFailed(uri, sa, ioe)
        }

        private fun local(host: String): Boolean =
            try {
                InetAddress.getAllByName(host).all {
                    it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress ||
                        it.isAnyLocalAddress
                }
            } catch (_: IOException) {
                false
            }
    }

    companion object {
        /** Connection targets refused since the last check. */
        val denied = CopyOnWriteArrayList<String>()
    }
}
