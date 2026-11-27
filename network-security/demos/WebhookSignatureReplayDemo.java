import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Demo 8 - Webhook signatures: a valid HMAC proves who wrote a request, not that it is new.
 *
 * A payment provider calls POST /webhooks/payment and signs each request with a shared secret.
 * Someone who captured one genuine request (a log, a proxy, a leaked dump) sends three things:
 * a modified body, the same request again, and the same request a day later.
 *
 * Run broken:  java -cp out WebhookSignatureReplayDemo broken   (signature = HMAC(secret, body))
 * Run fixed:   java -cp out WebhookSignatureReplayDemo fixed    (HMAC(secret, timestamp.body) + time window + event id)
 */
public class WebhookSignatureReplayDemo {

    /** Shared by provider and receiver. A real secret comes from a secret store, never from source code. */
    static final byte[] SECRET = "demo-webhook-secret-not-for-production".getBytes(StandardCharsets.UTF_8);
    static final Duration TOLERANCE = Duration.ofMinutes(5);

    static final AtomicLong creditedCents = new AtomicLong();
    static final Set<String> processedEventIds = ConcurrentHashMap.newKeySet();

    public static void main(String[] args) throws Exception {
        boolean fixed = args.length > 0 && args[0].equals("fixed");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/webhooks/payment", exchange -> receive(exchange, fixed));
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/webhooks/payment");
        HttpClient http = HttpClient.newHttpClient();
        System.out.printf("mode=%s signedContent=%s%n", fixed ? "fixed" : "broken", fixed ? "timestamp.body" : "body");

        String body = "{\"eventId\":\"evt_1001\",\"type\":\"payment.succeeded\",\"amountCents\":5000}";
        long now = Instant.now().getEpochSecond();
        String signature = sign(fixed ? now + "." + body : body);

        send(http, uri, "genuine event              ", body, now, signature);
        send(http, uri, "tampered body, same sig    ", body.replace("5000", "500000"), now, signature);
        send(http, uri, "replay of the same request ", body, now, signature);

        // A request captured yesterday. The attacker cannot re-sign, so in fixed mode the old timestamp stays.
        String oldBody = body.replace("evt_1001", "evt_0999");
        long yesterday = now - 86_400;
        send(http, uri, "captured request, 1 day old", oldBody, yesterday, sign(fixed ? yesterday + "." + oldBody : oldBody));
        server.stop(0);

        long credited = creditedCents.get();
        System.out.printf("account credited: %d cents (expected 5000)%n", credited);
        System.out.println(credited == 5000
                ? "RESULT: CORRECT (one genuine event, credited once)"
                : "RESULT: WRONG (replayed requests carry a valid signature and were processed again)");
    }

    private static void receive(HttpExchange exchange, boolean fixed) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String signature = exchange.getRequestHeaders().getFirst("X-Signature");
        String timestamp = exchange.getRequestHeaders().getFirst("X-Timestamp");
        int status = fixed ? verifyFixed(body, timestamp, signature) : verifyBroken(body, signature);
        if (status == 200) {
            creditedCents.addAndGet(Long.parseLong(field(body, "amountCents")));
        }
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    /** BUG: the signature covers only the body, and nothing remembers what was already processed. */
    private static int verifyBroken(String body, String signature) {
        return matches(sign(body), signature) ? 200 : 401;
    }

    private static int verifyFixed(String body, String timestamp, String signature) {
        // 1. authenticity: the timestamp is inside the signed content, so it cannot be changed either
        if (timestamp == null || !matches(sign(timestamp + "." + body), signature)) {
            return 401;
        }
        // 2. freshness: old captures expire
        long age = Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp));
        if (age > TOLERANCE.toSeconds()) {
            return 408;
        }
        // 3. uniqueness inside the window: each event id is processed once (idempotency)
        return processedEventIds.add(field(body, "eventId")) ? 200 : 409;
    }

    static String sign(String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison: the response time must not reveal how many characters matched. */
    private static boolean matches(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    /** Minimal field extraction for the fixed demo payload; real code uses a JSON parser. */
    private static String field(String json, String name) {
        int start = json.indexOf("\"" + name + "\":") + name.length() + 3;
        int end = start;
        while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) {
            end++;
        }
        return json.substring(start, end).replace("\"", "");
    }

    private static void send(HttpClient http, URI uri, String label, String body, long timestamp, String signature)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("X-Timestamp", Long.toString(timestamp))
                .header("X-Signature", signature)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        System.out.printf("%s -> %d %s%n", label, status, switch (status) {
            case 200 -> "processed";
            case 401 -> "bad signature";
            case 408 -> "timestamp outside the 5-minute window";
            case 409 -> "event id already processed";
            default -> "";
        });
    }
}
