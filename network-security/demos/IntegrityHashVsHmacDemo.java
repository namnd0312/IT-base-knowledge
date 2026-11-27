import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Demo 2 - Integrity: a plain hash does not protect a message against an active attacker.
 *
 * Scenario: the sender transmits "message + tag". An attacker on the path rewrites the
 * message and tries to produce a tag the receiver will accept.
 *
 * Run broken:  java -cp out IntegrityHashVsHmacDemo broken   (tag = SHA-256(message))
 * Run fixed:   java -cp out IntegrityHashVsHmacDemo fixed    (tag = HMAC-SHA256(key, message))
 */
public class IntegrityHashVsHmacDemo {

    /** Shared by sender and receiver only. A real key comes from a secret store, never from source code. */
    private static final byte[] SHARED_KEY = "demo-only-key-known-to-sender-and-receiver".getBytes(StandardCharsets.UTF_8);

    record Packet(String message, byte[] tag) {
    }

    public static void main(String[] args) throws Exception {
        boolean fixed = args.length > 0 && args[0].equals("fixed");
        System.out.printf("mode=%s tag=%s%n", fixed ? "fixed" : "broken", fixed ? "HMAC-SHA256(key, message)" : "SHA-256(message)");

        String original = "transfer 100 USD to alice";
        Packet sent = new Packet(original, fixed ? hmac(SHARED_KEY, original) : sha256(original));
        print("sender    ", sent);

        // The attacker sees the packet, changes the message and recomputes whatever it can compute.
        // It knows the algorithm (algorithms are public) but not the shared key.
        String forged = "transfer 9900 USD to mallory";
        byte[] forgedTag = fixed
                ? hmac("attacker-guess".getBytes(StandardCharsets.UTF_8), forged)
                : sha256(forged);
        Packet received = new Packet(forged, forgedTag);
        print("on the wire", received);

        byte[] expected = fixed ? hmac(SHARED_KEY, received.message()) : sha256(received.message());
        // MessageDigest.isEqual compares in constant time, so the check does not leak how many bytes matched
        boolean accepted = MessageDigest.isEqual(expected, received.tag());
        System.out.println("receiver accepts the packet: " + accepted);
        System.out.println(accepted
                ? "RESULT: WRONG (forged message accepted: a hash proves nothing about who computed it)"
                : "RESULT: CORRECT (forged message rejected: the tag cannot be produced without the key)");
    }

    private static byte[] sha256(String message) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(message.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, String message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
    }

    private static void print(String who, Packet packet) {
        System.out.printf("%s: \"%s\" tag=%s...%n", who, packet.message(),
                HexFormat.of().formatHex(packet.tag(), 0, 8));
    }
}
