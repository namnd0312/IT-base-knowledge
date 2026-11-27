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
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Demo 9 - Online guessing: a login endpoint that answers as fast as it is asked.
 *
 * A local HTTP server protects an account with a 4-digit PIN. The client simply
 * tries 0000, 0001, 0002... over HTTP.
 *
 * Run broken:  java -cp out LoginBruteForceRateLimitDemo broken   (unlimited attempts)
 * Run fixed:   java -cp out LoginBruteForceRateLimitDemo fixed    (5 failures lock the account for 60 s)
 *
 * The server binds to 127.0.0.1 on a random port and exists only for this run.
 */
public class LoginBruteForceRateLimitDemo {

    static final String SECRET_PIN = "7351";
    static final int MAX_FAILURES = 5;
    static final long LOCK_NANOS = 60_000_000_000L;

    private static int failures;
    private static long lockedUntilNanos;

    public static void main(String[] args) throws Exception {
        boolean fixed = args.length > 0 && args[0].equals("fixed");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/login", exchange -> respond(exchange, login(exchange, fixed)));
        server.start();
        URI loginUri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/login");
        System.out.printf(Locale.US, "mode=%s pinSpace=%,d limit=%s%n", fixed ? "fixed" : "broken", 10_000,
                fixed ? MAX_FAILURES + " failures then locked 60 s" : "none");

        HttpClient guesser = HttpClient.newHttpClient();
        Map<Integer, Integer> statusCounts = new TreeMap<>();
        String found = null;
        int attempts = 0;
        long start = System.nanoTime();
        for (int guess = 0; guess < 10_000 && found == null; guess++) {
            String pin = String.format("%04d", guess);
            HttpRequest request = HttpRequest.newBuilder(loginUri)
                    .POST(HttpRequest.BodyPublishers.ofString("pin=" + pin)).build();
            int status = guesser.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            statusCounts.merge(status, 1, Integer::sum);
            attempts++;
            if (status == 200) {
                found = pin;
            }
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        server.stop(0);

        System.out.printf(Locale.US, "attempts=%,d elapsed=%d ms responses=%s%n", attempts, elapsedMillis, statusCounts);
        System.out.println("pin found: " + (found == null ? "no" : found));
        System.out.println(found != null
                ? "RESULT: WRONG (the whole PIN space can be tried in seconds)"
                : "RESULT: CORRECT (only " + MAX_FAILURES + " guesses were evaluated, the rest got 429)");
    }

    /** Returns the HTTP status for one login attempt. */
    private static synchronized int login(HttpExchange exchange, boolean limited) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (limited && System.nanoTime() < lockedUntilNanos) {
            return 429; // locked: the PIN is not even compared, so a correct guess learns nothing
        }
        // constant-time comparison: the response time must not reveal how many characters matched
        boolean correct = MessageDigest.isEqual(
                ("pin=" + SECRET_PIN).getBytes(StandardCharsets.UTF_8), body.getBytes(StandardCharsets.UTF_8));
        if (correct) {
            failures = 0;
            return 200;
        }
        if (limited && ++failures >= MAX_FAILURES) {
            failures = 0;
            lockedUntilNanos = System.nanoTime() + LOCK_NANOS;
        }
        return 401;
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        if (status == 429) {
            exchange.getResponseHeaders().add("Retry-After", "60");
        }
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
