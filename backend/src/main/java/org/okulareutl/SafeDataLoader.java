// SPDX-License-Identifier: GPL-2.0-or-later
package org.okulareutl;

import eu.europa.esig.dss.model.DSSException;
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader;
import eu.europa.esig.dss.model.http.ResponseEnvelope;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.http.*;
import java.io.*;
import java.net.*;

/** Direct public-Internet HTTP(S) only, for TL, AIA, CRL and OCSP alike.
 * DNS answers are checked by the connection manager, not just before connecting.
 * No system proxy is used: a proxy would resolve the destination outside this policy.
 */
final class SafeDataLoader extends CommonsDataLoader {
    private final long limit;
    private final DnsResolver resolver;
    private final java.util.function.Predicate<InetAddress> addressPolicy;

    SafeDataLoader(long limit) { this(limit, SystemDefaultDnsResolver.INSTANCE); }
    SafeDataLoader(long limit, DnsResolver resolver) {
        this(limit, resolver, SafeDataLoader::publicAddress);
    }
    // Package-only injection for offline transport tests, never selected by configuration.
    SafeDataLoader(long limit, DnsResolver resolver, java.util.function.Predicate<InetAddress> addressPolicy) {
        this.limit = limit;
        this.resolver = resolver;
        this.addressPolicy = addressPolicy;
        setTimeoutConnection(15000);
        setTimeoutConnectionRequest(15000);
        setTimeoutResponse(20000);
        setTimeoutSocket(20000);
    }

    static URI checkUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException();
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT).replaceAll("\\.$", "");
            if (host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local")
                    || host.endsWith(".internal") || host.endsWith(".home.arpa") || (!host.contains(".") && !host.contains(":")))
                throw new IllegalArgumentException();
            // HttpClient may optimize literal addresses without asking its DNS resolver.
            if (host.startsWith("[") || host.matches("[0-9.]+"))
                checkAddresses(InetAddress.getAllByName(host));
            return uri;
        } catch (Exception e) { throw new DSSException("Tiltott hálózati cél (csak nyilvános HTTP/HTTPS): " + value, e); }
    }

    static boolean publicAddress(InetAddress address) {
        byte[] b = address.getAddress();
        int a = Byte.toUnsignedInt(b[0]), c = Byte.toUnsignedInt(b[1]);
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        if (b.length == 4) {
            return a != 0 && a != 10 && a != 127 && a < 224
                && !(a == 100 && c >= 64 && c <= 127)
                && !(a == 169 && c == 254) && !(a == 172 && c >= 16 && c <= 31)
                && !(a == 192 && (c == 168 || c == 0 && (Byte.toUnsignedInt(b[2]) == 0 || Byte.toUnsignedInt(b[2]) == 2)
                    || c == 88 && Byte.toUnsignedInt(b[2]) == 99))
                && !(a == 198 && (c == 18 || c == 19 || c == 51 && Byte.toUnsignedInt(b[2]) == 100))
                && !(a == 203 && c == 0 && Byte.toUnsignedInt(b[2]) == 113);
        }
        // Global unicast only; exclude protocol assignments, documentation and 6to4.
        return b.length == 16 && (a & 0xe0) == 0x20
            && !(a == 0x20 && c == 1 && Byte.toUnsignedInt(b[2]) < 2)
            && !(a == 0x20 && c == 1 && Byte.toUnsignedInt(b[2]) == 0x0d && Byte.toUnsignedInt(b[3]) == 0xb8)
            && !(a == 0x20 && c == 2)
            && !(a == 0x3f && c == 0xff && (Byte.toUnsignedInt(b[2]) & 0xf0) == 0);
    }

    static InetAddress[] checkAddresses(InetAddress[] addresses) throws UnknownHostException {
        if (addresses.length == 0) throw new UnknownHostException("Üres DNS-válasz");
        for (var address : addresses)
            if (!publicAddress(address)) throw new UnknownHostException("Nem nyilvános hálózati cél tiltva");
        return addresses;
    }

    @Override public byte[] get(String url) { checkUrl(url); return super.get(url); }

    @Override protected HttpClientConnectionManager getConnectionManager() {
        DnsResolver checked = new DnsResolver() {
            public InetAddress[] resolve(String host) throws UnknownHostException {
                var addresses = resolver.resolve(host);
                if (addresses.length == 0) throw new UnknownHostException("Üres DNS-válasz");
                for (var address : addresses)
                    if (!addressPolicy.test(address)) throw new UnknownHostException("Nem nyilvános hálózati cél tiltva");
                return addresses;
            }
            public String resolveCanonicalHostname(String host) throws UnknownHostException { return host; }
        };
        return PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(checked).setTlsSocketStrategy(getTlsSocketStrategy())
            .setDefaultSocketConfig(getSocketConfig())
            .setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(15)).build())
            .setMaxConnTotal(8).setMaxConnPerRoute(4).build();
    }

    @Override protected synchronized HttpClientBuilder getHttpClientBuilder(String url) {
        checkUrl(url);
        // Each redirect is handled below without draining its untrusted response body.
        return super.getHttpClientBuilder(url).disableAutomaticRetries().disableRedirectHandling().disableCookieManagement();
    }

    static byte[] readLimited(InputStream stream, long declaredSize, long limit) throws IOException {
        if (declaredSize > limit) throw new IOException("A hálózati válasz túl nagy");
        var out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long count = 0;
        int read;
        while ((read = stream.read(buffer, 0, (int)Math.min(buffer.length, limit - count + 1))) != -1) {
            count += read;
            if (count > limit) throw new IOException("A hálózati válasz túllépte a méretkorlátot");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    @Override protected ResponseEnvelope executeHttpRequest(CloseableHttpClient client, HttpUriRequest request,
            boolean details, boolean body) throws IOException {
        HttpUriRequestBase current = (HttpUriRequestBase) request;
        for (int redirects = 0; redirects <= 5; redirects++) {
            final HttpUriRequestBase hop = current;
            final URI source;
            try { source = checkUrl(hop.getUri().toString()); }
            catch (URISyntaxException e) { throw new IOException(e); }
            var result = new ResponseEnvelope();
            final String[] location = {null};
            final int[] status = {0};
            byte[] bytes = client.execute(getHttpHost(hop), hop, getHttpContext(getHttpHost(hop)), response -> {
                try {
                    status[0] = response.getCode();
                    if (status[0] == 301 || status[0] == 302 || status[0] == 303 || status[0] == 307 || status[0] == 308) {
                        var header = response.getFirstHeader("Location");
                        if (header == null) throw new IOException("Hiányzó átirányítási cím");
                        location[0] = header.getValue();
                        // Do not let an attacker stream an unlimited intermediate body.
                        hop.cancel();
                        return new byte[0];
                    }
                    if (status[0] != 200 || response.getEntity() == null)
                        throw new IOException("Hibás hálózati válasz: HTTP " + status[0]);
                    if (details) result.setHeaders(toHeadersMap(response.getHeaders()));
                    if (!body) { hop.cancel(); return new byte[0]; }
                    // HttpClient has decompressed gzip/deflate; bound expanded bytes too.
                    return readLimited(response.getEntity().getContent(), response.getEntity().getContentLength(), limit);
                } catch (IOException | RuntimeException e) {
                    // Abort before HttpClient can drain an oversized response on cleanup.
                    hop.cancel();
                    throw e;
                }
            });
            if (location[0] == null) { result.setResponseBody(bytes); return result; }
            if (redirects == 5) throw new IOException("Túl sok átirányítás");
            URI target = checkUrl(source.resolve(location[0]).toString());
            if ("https".equalsIgnoreCase(source.getScheme()) && "http".equalsIgnoreCase(target.getScheme()))
                throw new IOException("HTTPS-ről HTTP-re átirányítás tiltva");
            if ("GET".equals(hop.getMethod())) current = new HttpGet(target);
            else if ("POST".equals(hop.getMethod()) && (status[0] == 307 || status[0] == 308)) {
                var post = new HttpPost(target);
                post.setEntity(hop.getEntity());
                post.setHeader("Content-Type", contentType);
                current = post;
            } else throw new IOException("Nem biztonságos POST-átirányítás tiltva");
        }
        throw new IOException("Túl sok átirányítás");
    }
}
