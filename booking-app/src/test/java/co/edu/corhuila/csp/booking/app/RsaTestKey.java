package co.edu.corhuila.csp.booking.app;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

/**
 * A throwaway RSA key pair for the tests: no key material of the service is committed to this
 * repository, and none is needed from {@code csp-infra}. The PEM is exactly what
 * {@code JWT_PUBLIC_KEY} would carry in a real environment.
 */
final class RsaTestKey {

    private final KeyPair keyPair;

    private RsaTestKey(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    static RsaTestKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return new RsaTestKey(generator.generateKeyPair());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("the test key pair could not be generated", exception);
        }
    }

    String publicKeyPem() {
        String base64 = Base64.getEncoder().encodeToString(publicKey().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }

    RSAPublicKey publicKey() {
        return (RSAPublicKey) keyPair.getPublic();
    }
}
