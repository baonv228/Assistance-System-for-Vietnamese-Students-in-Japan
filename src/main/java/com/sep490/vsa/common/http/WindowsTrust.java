package com.sep490.vsa.common.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Request factories for the outside services the server calls (OpenStreetMap, OpenAI, Gemini). On Windows they also
 * trust the Windows certificate store, like browsers do: antivirus software that scans HTTPS (Kaspersky, Avast, ESET…)
 * and company proxies re-sign the sites' certificates with their own root, which Java alone rejects. Elsewhere, or if
 * the store cannot be read, Java's own certificates are used.
 */
public final class WindowsTrust {

    private static final Logger log = LoggerFactory.getLogger(WindowsTrust.class);

    private WindowsTrust() {
    }

    /** The certificates are read once, the first time a factory is made. */
    private static final class Holder {
        static final SSLSocketFactory SSL = windowsAwareSsl();
    }

    /** A request factory with these timeouts that trusts Java's certificates and, on Windows, the Windows store. */
    public static SimpleClientHttpRequestFactory requestFactory(int connectTimeoutMs, int readTimeoutMs) {
        SSLSocketFactory ssl = Holder.SSL;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                if (ssl != null && connection instanceof HttpsURLConnection https) {
                    https.setSSLSocketFactory(ssl);
                }
            }
        };
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }

    /**
     * On Windows: trust Java's own certificates and those of the Windows store (where antivirus HTTPS scanning and
     * company proxies put their root). Elsewhere, or if the store cannot be read: null, Java's default is used.
     */
    static SSLSocketFactory windowsAwareSsl() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return null;
        }
        try {
            X509TrustManager java = trustManager(null);
            KeyStore windows = KeyStore.getInstance("Windows-ROOT");
            windows.load(null, null);
            X509TrustManager system = trustManager(windows);
            X509TrustManager both = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    java.checkClientTrusted(chain, authType);
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                    try {
                        java.checkServerTrusted(chain, authType);
                    } catch (CertificateException notInJava) {
                        system.checkServerTrusted(chain, authType);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return Stream.concat(Arrays.stream(java.getAcceptedIssuers()),
                            Arrays.stream(system.getAcceptedIssuers())).toArray(X509Certificate[]::new);
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{both}, null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException | IOException e) {
            log.warn("Could not read the Windows certificate store, using Java's certificates only: {}", e.getMessage());
            return null;
        }
    }

    private static X509TrustManager trustManager(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        return Arrays.stream(factory.getTrustManagers()).filter(X509TrustManager.class::isInstance)
                .map(X509TrustManager.class::cast).findFirst()
                .orElseThrow(() -> new GeneralSecurityException("no X509TrustManager"));
    }
}
