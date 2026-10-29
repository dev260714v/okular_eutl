// SPDX-License-Identifier: GPL-2.0-or-later
package org.okulareutl;

import com.sun.net.httpserver.HttpServer;
import eu.europa.esig.dss.model.DSSException;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class SafeDataLoaderTest {
    HttpServer server;
    String base;
    AtomicInteger requests = new AtomicInteger();
    DnsResolver localDns = new DnsResolver() {
        public InetAddress[] resolve(String host) throws UnknownHostException { return new InetAddress[]{InetAddress.getByName("127.0.0.1")}; }
        public String resolveCanonicalHostname(String host) { return host; }
    };
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://transport.example:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            byte[] response = path.equals("/small") ? new byte[]{1,2,3} : new byte[8192];
            if (path.equals("/redirect") || path.equals("/private") || path.equals("/loop") || path.startsWith("/post-") || path.equals("/redirect-large")) {
                exchange.getResponseHeaders().add("Location", path.equals("/private") ? "http://127.0.0.1:" + server.getAddress().getPort() + "/small" : path.equals("/loop") ? "/loop" : "/small");
                exchange.sendResponseHeaders(path.equals("/post-preserved") ? 307 : 302, path.equals("/redirect-large") ? response.length : -1);
                if (path.equals("/redirect-large")) try (var out = exchange.getResponseBody()) { out.write(response); } catch (IOException ignored) { }
                exchange.close(); return;
            }
            if (path.equals("/gzip")) {
                var out = new ByteArrayOutputStream();
                try (var gzip = new GZIPOutputStream(out)) { gzip.write(response); }
                response = out.toByteArray(); exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            }
            exchange.sendResponseHeaders(200, path.equals("/chunked") ? 0 : response.length);
            try (var out = exchange.getResponseBody()) { out.write(response); }
            catch (IOException ignored) { /* client abort at the configured cap is expected */ }
            finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    SafeDataLoader localTransport() { return new SafeDataLoader(1024, localDns, InetAddress::isLoopbackAddress); }

    @ParameterizedTest @ValueSource(strings={"127.0.0.1","127.1","0.0.0.0","10.1.2.3","172.16.0.1","192.168.1.1","169.254.169.254","100.64.0.1","192.0.2.1","198.18.0.1","198.51.100.1","203.0.113.1","224.0.0.1","240.0.0.1","::1","fc00::1","fe80::1","::ffff:127.0.0.1","64:ff9b::a00:1","2001:db8::1","2002:7f00:1::1"})
    void rejectsNonPublicAddresses(String ip) throws Exception { assertFalse(SafeDataLoader.publicAddress(InetAddress.getByName(ip))); }
    @ParameterizedTest @ValueSource(strings={"1.1.1.1","8.8.8.8","2001:4860:4860::8888","2606:4700:4700::1111"})
    void permitsGlobalAddresses(String ip) throws Exception { assertTrue(SafeDataLoader.publicAddress(InetAddress.getByName(ip))); }
    @ParameterizedTest @ValueSource(strings={"file:///etc/hosts","ldap://directory.example/a","ftp://example.com/a","http://localhost/a","http://service.local/a","http://user:pass@example.com/a","http://[::1]/a","http://2130706433/a","http://127.1/a"})
    void rejectsUnsafeUrlsBeforeTransport(String url) { assertThrows(DSSException.class, () -> new SafeDataLoader(1024).get(url)); }
    @Test void actualConnectionRejectsPrivateDnsAnswerForGetAndOcspPost() {
        var loader = new SafeDataLoader(1024, localDns);
        assertThrows(DSSException.class, () -> loader.get(base + "/small"));
        loader.setContentType("application/ocsp-request");
        assertThrows(DSSException.class, () -> loader.post(base + "/small", new byte[]{1}));
        assertEquals(0, requests.get());
    }
    @Test void dnsAnswerContainingBothPublicAndPrivateAddressesIsRejected() throws Exception {
        assertThrows(UnknownHostException.class, () -> SafeDataLoader.checkAddresses(new InetAddress[]{InetAddress.getByName("1.1.1.1"), InetAddress.getByName("127.0.0.1")}));
    }
    @Test void followsRelativePublicRedirects() { assertArrayEquals(new byte[]{1,2,3}, localTransport().get(base + "/redirect")); assertEquals(2, requests.get()); }
    @Test void redirectCannotReachPrivateLiteral() { assertThrows(DSSException.class, () -> localTransport().get(base + "/private")); assertEquals(1, requests.get()); }
    @Test void redirectLoopStopsAfterFiveHops() { assertThrows(DSSException.class, () -> localTransport().get(base + "/loop")); assertEquals(6, requests.get()); }
    @Test void oversizedIntermediateBodyIsAbortedInsteadOfBuffered() { assertArrayEquals(new byte[]{1,2,3},localTransport().get(base + "/redirect-large")); }
    @Test void ocspPostPreservingRedirectCanBeFollowed() { assertArrayEquals(new byte[]{1,2,3},localTransport().post(base + "/post-preserved",new byte[]{1})); }
    @Test void ambiguousPostRedirectIsRejected() { assertThrows(DSSException.class, () -> localTransport().post(base + "/post-ambiguous",new byte[]{1})); assertEquals(1,requests.get()); }
    @ParameterizedTest @ValueSource(strings={"/large","/chunked","/gzip"})
    void limitsActualNetworkBodiesIncludingExpandedGzip(String path) { assertThrows(DSSException.class, () -> localTransport().get(base + path)); }
    @Test void limitsOcspPostResponse() { assertThrows(DSSException.class, () -> localTransport().post(base + "/large", new byte[]{1})); }
    @Test void readerStopsAfterLimitPlusOneRatherThanDrainingStream() {
        var count = new AtomicInteger();
        InputStream endless = new InputStream() { public int read() { count.incrementAndGet(); return 42; } };
        assertThrows(IOException.class, () -> SafeDataLoader.readLimited(endless, -1, 1024));
        assertEquals(1025, count.get());
    }
}
