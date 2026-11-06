package co.edu.corhuila.csp.booking.adapter.out.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * A certificate authority and the server certificates it signs, generated with the {@code keytool} of the JDK in a
 * directory of the test. Nothing is committed and nothing outlives the test (ADR-067).
 */
final class ThrowawayPki {

    private static final char[] PASSWORD = "changeit".toCharArray();

    private final Path directory;
    private final String name;
    private final Path authorityStore;

    private ThrowawayPki(Path directory, String name) {
        this.directory = directory;
        this.name = name;
        this.authorityStore = directory.resolve(name + ".p12");
    }

    static ThrowawayPki authority(Path directory, String name) {
        ThrowawayPki pki = new ThrowawayPki(directory, name);
        keytool("-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=" + name,
                "-ext", "bc:c", "-ext", "ku:c=keyCertSign,cRLSign", "-validity", "2",
                "-keystore", pki.authorityStore.toString(), "-storetype", "PKCS12", "-storepass", "changeit");
        return pki;
    }

    /** The public certificate of the authority in PEM, the file a caller receives as the CA file. */
    Path certificateFile() {
        Path file = directory.resolve(name + ".crt");
        keytool("-exportcert", "-rfc", "-alias", name, "-keystore", authorityStore.toString(),
                "-storepass", "changeit", "-file", file.toString());
        return file;
    }

    /** A server SSL context whose certificate carries exactly the given DNS names and is signed by this authority. */
    SSLContext serverContext(String... dnsNames) throws Exception {
        String alias = "server-" + System.nanoTime();
        Path serverStore = directory.resolve(alias + ".p12");
        Path request = directory.resolve(alias + ".csr");
        Path signed = directory.resolve(alias + ".pem");
        StringBuilder san = new StringBuilder("san=");
        for (int i = 0; i < dnsNames.length; i++) {
            san.append(i == 0 ? "" : ",").append("dns:").append(dnsNames[i]);
        }
        keytool("-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=" + dnsNames[0],
                "-validity", "2", "-keystore", serverStore.toString(), "-storetype", "PKCS12", "-storepass", "changeit");
        keytool("-certreq", "-alias", alias, "-keystore", serverStore.toString(), "-storepass", "changeit",
                "-file", request.toString());
        keytool("-gencert", "-alias", name, "-keystore", authorityStore.toString(), "-storepass", "changeit",
                "-ext", san.toString(), "-validity", "2", "-rfc", "-infile", request.toString(),
                "-outfile", signed.toString());
        keytool("-importcert", "-noprompt", "-alias", name, "-file", certificateFile().toString(),
                "-keystore", serverStore.toString(), "-storepass", "changeit");
        keytool("-importcert", "-noprompt", "-alias", alias, "-file", signed.toString(),
                "-keystore", serverStore.toString(), "-storepass", "changeit");

        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(serverStore)) {
            keys.load(in, PASSWORD);
        }
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keys, PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), null, null);
        return context;
    }

    private static void keytool(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            if (process.waitFor() != 0) {
                throw new IllegalStateException("keytool failed: " + output);
            }
        } catch (IOException failed) {
            throw new IllegalStateException("keytool cannot be run", failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("keytool was interrupted", interrupted);
        }
    }
}
