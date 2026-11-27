import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Demo 5 - Certificate validation: TLS without checking the certificate is not secure.
 *
 * Scenario: the client wants to call its API server, but the network has been tampered
 * with (DNS or ARP spoofing) and the connection lands on an impostor. The impostor
 * also speaks TLS and also has a certificate for "localhost" - one it made itself.
 *
 * Run broken:  java -cp out TlsCertificateValidationDemo broken   (client trusts any certificate)
 * Run fixed:   java -cp out TlsCertificateValidationDemo fixed    (client trusts only the demo CA)
 *
 * Needs out/pki (./demos/create-demo-pki.sh).
 */
public class TlsCertificateValidationDemo {

    static final String REQUEST = "GET /account Authorization: Bearer demo-api-token-12345";

    public static void main(String[] args) throws Exception {
        boolean fixed = args.length > 0 && args[0].equals("fixed");
        System.out.println("mode=" + (fixed ? "fixed" : "broken"));

        List<String> stolen = new CopyOnWriteArrayList<>();
        int realPort = startServer(DemoTlsSupport.context("server", null), new CopyOnWriteArrayList<>());
        int impostorPort = startServer(DemoTlsSupport.context("attacker", null), stolen);

        SSLContext clientContext = fixed ? DemoTlsSupport.context(null, "truststore") : trustEverything();

        System.out.println("client -> impostor: " + call(clientContext, impostorPort, fixed));
        if (fixed) {
            System.out.println("client -> real server: " + call(clientContext, realPort, true));
        }

        System.out.println("impostor captured: " + (stolen.isEmpty() ? "nothing" : stolen.get(0)));
        System.out.println(stolen.isEmpty()
                ? "RESULT: CORRECT (handshake with the impostor was refused, the token never left the client)"
                : "RESULT: WRONG (encrypted connection, but to the attacker)");
    }

    /** BUG: the classic "make the certificate error go away" snippet - it accepts any certificate. */
    private static SSLContext trustEverything() throws Exception {
        TrustManager acceptAll = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {acceptAll}, new SecureRandom());
        return context;
    }

    private static String call(SSLContext context, int port, boolean verifyHostname) throws Exception {
        try (SSLSocket socket = DemoTlsSupport.connect(context, port, verifyHostname)) {
            socket.startHandshake(); // certificate checks happen here, before any application data
            X509Certificate peer = (X509Certificate) socket.getSession().getPeerCertificates()[0];
            socket.getOutputStream().write((REQUEST + "\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            String reply = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            return "handshake OK, peer certificate [" + peer.getSubjectX500Principal().getName()
                    + "], request sent, reply: " + reply;
        } catch (SSLException e) {
            return "handshake REFUSED: " + e.getMessage();
        }
    }

    /** A TLS server that records the first line of each request it manages to read. */
    private static int startServer(SSLContext context, List<String> received) throws Exception {
        ServerSocket server = context.getServerSocketFactory()
                .createServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(() -> {
            while (true) {
                try (Socket socket = server.accept()) {
                    String line = new BufferedReader(
                            new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
                    if (line != null) {
                        received.add(line);
                        socket.getOutputStream().write("200 OK\n".getBytes(StandardCharsets.UTF_8));
                        socket.getOutputStream().flush();
                    }
                } catch (Exception e) {
                    // the client aborted the handshake: nothing was received
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return server.getLocalPort();
    }
}
