import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;

/**
 * Demo 6 - One-way TLS vs mutual TLS: how a server knows WHICH service is calling it.
 *
 * An internal endpoint must only serve "order-service". Two callers try it: the real
 * order-service and a rogue process that simply claims to be order-service.
 *
 * Run broken:  java -cp out MutualTlsDemo broken   (one-way TLS, caller identity taken from a header)
 * Run fixed:   java -cp out MutualTlsDemo fixed    (mTLS, caller identity taken from the client certificate)
 *
 * Needs out/pki (./demos/create-demo-pki.sh). Everything runs on 127.0.0.1.
 */
public class MutualTlsDemo {

    static final String ALLOWED_SERVICE = "order-service";

    public static void main(String[] args) throws Exception {
        boolean mutual = args.length > 0 && args[0].equals("fixed");
        System.out.printf("mode=%s server=%s%n", mutual ? "fixed" : "broken",
                mutual ? "needClientAuth=true, identity = CN of the verified client certificate"
                        : "one-way TLS, identity = X-Service-Name header");

        // The server always presents server.p12. With mTLS it also needs a truststore: which client CAs it accepts.
        SSLServerSocket server = (SSLServerSocket) DemoTlsSupport.context("server", mutual ? "truststore" : null)
                .getServerSocketFactory().createServerSocket(0, 50, InetAddress.getLoopbackAddress());
        server.setNeedClientAuth(mutual);
        Thread serverThread = new Thread(() -> serve(server, mutual));
        serverThread.setDaemon(true);
        serverThread.start();
        int port = server.getLocalPort();

        String legit = call("real order-service ", DemoTlsSupport.context(mutual ? "client" : null, "truststore"), port);
        String rogueNoCert = call("rogue, no certificate", DemoTlsSupport.context(null, "truststore"), port);
        String rogueSelfSigned = mutual
                ? call("rogue, self-signed CN=order-service", DemoTlsSupport.context("rogue-client", "truststore"), port)
                : "";

        boolean rogueServed = rogueNoCert.startsWith("200") || rogueSelfSigned.startsWith("200");
        System.out.println(!legit.startsWith("200") ? "RESULT: WRONG (the real service was rejected)"
                : rogueServed ? "RESULT: WRONG (anyone who can reach the port can claim to be order-service)"
                : "RESULT: CORRECT (only a holder of a CA-signed client key is accepted)");
    }

    private static String call(String who, SSLContext context, int port) {
        String reply;
        try (SSLSocket socket = DemoTlsSupport.connect(context, port, true)) {
            socket.getOutputStream().write(("X-Service-Name: " + ALLOWED_SERVICE + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String line = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            reply = line == null ? "connection closed by server" : line;
        } catch (Exception e) {
            // With TLS 1.3 the client learns about a rejected certificate on its first read or write
            reply = "TLS failure: " + e.getMessage();
        }
        System.out.printf("%-36s -> %s%n", who, reply);
        return reply;
    }

    private static void serve(SSLServerSocket server, boolean mutual) {
        while (true) {
            try (SSLSocket socket = (SSLSocket) server.accept()) {
                String header = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
                String caller = mutual
                        ? commonName((X509Certificate) socket.getSession().getPeerCertificates()[0])
                        : header.substring(header.indexOf(':') + 1).trim();   // BUG: self-declared identity
                String reply = ALLOWED_SERVICE.equals(caller)
                        ? "200 orders for caller '" + caller + "'"
                        : "403 caller '" + caller + "' is not allowed";
                socket.getOutputStream().write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
            } catch (IOException | RuntimeException e) {
                // handshake refused (no or untrusted client certificate): nothing was served
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static String commonName(X509Certificate certificate) throws Exception {
        for (Rdn rdn : new LdapName(certificate.getSubjectX500Principal().getName()).getRdns()) {
            if (rdn.getType().equalsIgnoreCase("CN")) {
                return rdn.getValue().toString();
            }
        }
        return "";
    }
}
