import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocket;

/**
 * Demo 1 - Eavesdropping: what a machine in the middle of the path can read.
 *
 * Scenario: a client logs in to a server. Every byte passes through a relay, which
 * stands for any device on the path (Wi-Fi access point, router, ISP, proxy). The
 * relay forwards faithfully and keeps a copy of what the client sent.
 *
 * Run broken:  java -cp out PlaintextVsTlsEavesdropDemo broken   (plain TCP)
 * Run fixed:   java -cp out PlaintextVsTlsEavesdropDemo fixed    (TLS)
 *
 * The fixed mode needs out/pki (./demos/create-demo-pki.sh). Everything runs inside this JVM on 127.0.0.1.
 */
public class PlaintextVsTlsEavesdropDemo {

    static final String PASSWORD = "S3cret-Pa55";
    static final String LOGIN = "LOGIN user=alice password=" + PASSWORD;

    public static void main(String[] args) throws Exception {
        boolean tls = args.length > 0 && args[0].equals("fixed");
        System.out.printf("mode=%s transport=%s%n", tls ? "fixed" : "broken", tls ? "TLS" : "plain TCP");

        ServerSocket server = tls
                ? DemoTlsSupport.context("server", null).getServerSocketFactory()
                        .createServerSocket(0, 50, InetAddress.getLoopbackAddress())
                : new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        ServerSocket relay = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        ByteArrayOutputStream captured = new ByteArrayOutputStream();

        daemon(() -> serveOneLogin(server));
        Thread relayThread = daemon(() -> relayOneConnection(relay, server.getLocalPort(), captured));

        String reply;
        try (Socket client = tls
                ? DemoTlsSupport.connect(DemoTlsSupport.context(null, "truststore"), relay.getLocalPort(), true)
                : new Socket(InetAddress.getLoopbackAddress(), relay.getLocalPort())) {
            client.getOutputStream().write((LOGIN + "\n").getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            reply = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8)).readLine();
            if (client instanceof SSLSocket secure) {
                System.out.printf("negotiated:       %s %s%n",
                        secure.getSession().getProtocol(), secure.getSession().getCipherSuite());
            }
        }
        relayThread.join(2000);

        byte[] seen = captured.toByteArray();
        boolean leaked = new String(seen, StandardCharsets.ISO_8859_1).contains(PASSWORD);
        System.out.println("client sent:      " + LOGIN);
        System.out.println("server replied:   " + reply);
        System.out.printf("eavesdropper saw: %d bytes: %s%n", seen.length, printable(seen, 96));
        System.out.println("password visible on the wire: " + leaked);
        System.out.println(leaked
                ? "RESULT: WRONG (anyone on the path can read the password)"
                : "RESULT: CORRECT (the path only carries ciphertext)");
    }

    private static void serveOneLogin(ServerSocket server) throws IOException {
        try (Socket socket = server.accept()) {
            new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            socket.getOutputStream().write("OK welcome alice\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        }
    }

    /** Forwards bytes in both directions and records the client -> server direction. */
    private static void relayOneConnection(ServerSocket relay, int serverPort, OutputStream captured)
            throws Exception {
        try (Socket fromClient = relay.accept();
             Socket toServer = new Socket(InetAddress.getLoopbackAddress(), serverPort)) {
            Thread back = daemon(() -> pump(toServer.getInputStream(), fromClient.getOutputStream(), null));
            pump(fromClient.getInputStream(), toServer.getOutputStream(), captured);
            back.join(1000);
        }
    }

    private static void pump(InputStream in, OutputStream out, OutputStream copy) throws IOException {
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (copy != null) {
                copy.write(buffer, 0, read);
            }
            out.write(buffer, 0, read);
            out.flush();
        }
    }

    /** Renders bytes the way a packet sniffer does: printable ASCII as is, everything else as a dot. */
    private static String printable(byte[] bytes, int limit) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, limit); i++) {
            int b = bytes[i] & 0xff;
            text.append(b >= 32 && b < 127 ? (char) b : '.');
        }
        return bytes.length > limit ? text + "..." : text.toString();
    }

    interface Step {
        void run() throws Exception;
    }

    private static Thread daemon(Step step) {
        Thread thread = new Thread(() -> {
            try {
                step.run();
            } catch (Exception e) {
                // a closed socket ends the relay; nothing to report
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
