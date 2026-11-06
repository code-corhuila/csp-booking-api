package co.edu.corhuila.csp.booking.adapter.out.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * The trust of the call to Catalog: the certificate authority in the PEM file {@code CATALOG_TLS_CA_FILE} and nothing
 * else, so the authorities of the JDK are not trusted for this call (ADR-067 section 3). The context has no key manager
 * (there is no mutual TLS) and checks the name of the server: the client that uses it keeps endpoint identification on.
 */
public final class TrustedCa {

    private TrustedCa() {
    }

    public static SSLContext sslContext(Path caFile) {
        try {
            Collection<? extends Certificate> authorities;
            try (InputStream in = Files.newInputStream(caFile)) {
                authorities = CertificateFactory.getInstance("X.509").generateCertificates(in);
            }
            if (authorities.isEmpty()) {
                throw new IllegalStateException("the CA file has no certificate");
            }
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            int index = 0;
            for (Certificate authority : authorities) {
                trust.setCertificateEntry("csp-internal-ca-" + index++, authority);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trust);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (IOException | GeneralSecurityException unreadable) {
            throw new IllegalStateException("the CA file cannot be read as certificates", unreadable);
        }
    }
}
