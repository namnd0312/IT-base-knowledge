package demo.netsec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Real HTTPS round trips against the running server (random port), using the same PKI as the
 * plain-Java demos. These clients are also the reference for calling an mTLS service from Java.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MutualTlsAndJwtIntegrationTests {

    private static final Path PKI = Path.of("../out/pki");
    private static final char[] PASSWORD = "changeit".toCharArray();

    @LocalServerPort
    int port;

    @Autowired
    DemoTokenIssuer tokenIssuer;

    @Test
    void trustedServiceCertificateIsAuthenticatedByItsCommonName() throws Exception {
        HttpResponse<String> response = get(client("client"), "/internal/inventory", null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"caller\":\"order-service\"", "ROLE_SERVICE");
    }

    @Test
    void connectionWithoutClientCertificateCannotReachInternalEndpoints() throws Exception {
        assertThat(get(client(null), "/internal/inventory", null).statusCode()).isEqualTo(403);
    }

    @Test
    void certificateNotSignedByTheTrustedCaIsNeverAccepted() throws Exception {
        // The server's CertificateRequest lists the CAs it accepts (CN=Demo Root CA). The JDK key manager
        // only offers a certificate issued by one of them, so this self-signed one is not sent at all:
        // the connection continues without a client certificate and the chain answers 403.
        // (curl sends it anyway; the server then aborts the handshake with "certificate unknown".)
        assertThat(get(client("rogue-client"), "/internal/inventory", null).statusCode()).isEqualTo(403);
    }

    @Test
    void serverWhoseCertificateIsNotSignedByTheTrustedCaIsRefusedByTheClient() {
        // The client side of the same rule: an HttpClient that trusts only the demo CA refuses a server
        // certificate that does not chain to it - here, the JDK default cacerts does not know the demo CA.
        assertThatThrownBy(() -> HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/api/orders")).build(),
                HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(SSLHandshakeException.class)
                .hasMessageContaining("PKIX path building failed");
    }

    @Test
    void validTokenForThisAudienceIsAccepted() throws Exception {
        String token = tokenIssuer.issue("alice", "orders-api", "orders:read", Duration.ofMinutes(5));
        HttpResponse<String> response = get(client(null), "/api/orders", token);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"subject\":\"alice\"");
    }

    @Test
    void genuineTokenIssuedForAnotherApiIsRejected() throws Exception {
        String token = tokenIssuer.issue("alice", "billing-api", "orders:read", Duration.ofMinutes(5));
        HttpResponse<String> response = get(client(null), "/api/orders", token);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                header -> assertThat(header).contains("The aud claim is not valid"));
    }

    @Test
    void tokenWithoutRequiredScopeIsForbidden() throws Exception {
        String token = tokenIssuer.issue("alice", "orders-api", "orders:read", Duration.ofMinutes(5));
        assertThat(get(client(null), "/api/admin/orders", token).statusCode()).isEqualTo(403);
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String[] parts = tokenIssuer.issue("alice", "orders-api", "orders:read", Duration.ofMinutes(5)).split("\\.");
        String forged = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "xx." + parts[2];
        assertThat(get(client(null), "/api/orders", forged).statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(HttpClient client, String path, String bearerToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("https://localhost:" + port + path));
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * keyStoreName = the client identity to present (null for no certificate).
     * The truststore always holds the demo CA: that is how the client verifies the SERVER.
     * HttpClient checks the host name against the certificate SAN by itself.
     */
    private static HttpClient client(String keyStoreName) throws Exception {
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(load("truststore"));
        KeyManagerFactory keys = null;
        if (keyStoreName != null) {
            keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(load(keyStoreName), PASSWORD);
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys == null ? null : keys.getKeyManagers(), trust.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(context).build();
    }

    private static KeyStore load(String name) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(PKI.resolve(name + ".p12"))) {
            store.load(in, PASSWORD);
        }
        return store;
    }
}
