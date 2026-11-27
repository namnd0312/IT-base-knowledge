import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * Shared TLS helpers for the TLS demos.
 *
 * Key material comes from out/pki/, created by demos/create-demo-pki.sh:
 *   server.p12, client.p12        key + certificate chain signed by the demo CA
 *   truststore.p12                the demo CA certificate only
 *   attacker.p12, rogue-client.p12  self-signed, not signed by the CA
 */
final class DemoTlsSupport {

    private static final char[] PASSWORD = "changeit".toCharArray(); // demo value, loopback-only keys
    private static final Path PKI_DIR = Path.of("out", "pki");

    private DemoTlsSupport() {
    }

    static KeyStore load(String name) throws Exception {
        Path file = PKI_DIR.resolve(name + ".p12");
        if (Files.notExists(file)) {
            throw new IllegalStateException(file + " not found - run ./demos/create-demo-pki.sh from network-security/ first");
        }
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            keyStore.load(in, PASSWORD);
        }
        return keyStore;
    }

    /**
     * Builds an SSLContext from the two halves of a TLS identity decision:
     *   keyStoreName   - "who am I": the private key and certificate chain this side presents (null = none)
     *   trustStoreName - "whom do I believe": the CA certificates this side accepts (null = the JDK default cacerts)
     */
    static SSLContext context(String keyStoreName, String trustStoreName) throws Exception {
        KeyManager[] keyManagers = null;
        if (keyStoreName != null) {
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(load(keyStoreName), PASSWORD);
            keyManagers = keys.getKeyManagers();
        }
        TrustManager[] trustManagers = null;
        if (trustStoreName != null) {
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(load(trustStoreName));
            trustManagers = trust.getTrustManagers();
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers, trustManagers, null);
        return context;
    }

    /**
     * Opens a TLS connection to a loopback port, expecting the peer to be "localhost".
     * verifyHostname=true turns on the check that the certificate was issued for that name;
     * a raw SSLSocket does NOT do this by default.
     */
    static SSLSocket connect(SSLContext context, int port, boolean verifyHostname) throws Exception {
        Socket plain = new Socket(InetAddress.getLoopbackAddress(), port);
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket(plain, "localhost", port, true);
        if (verifyHostname) {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            socket.setSSLParameters(parameters);
        }
        return socket;
    }
}
