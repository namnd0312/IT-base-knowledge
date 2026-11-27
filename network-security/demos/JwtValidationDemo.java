import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Demo 7 - JWT validation by hand: what "validating a token" actually consists of.
 *
 * A JWT is header.payload.signature, each part Base64URL. Decoding it is not validating it:
 * the payload is readable and writable by anyone. Four bad tokens are presented to a resource server.
 *
 * Run broken:  java -cp out JwtValidationDemo broken   (decode the payload and trust it)
 * Run fixed:   java -cp out JwtValidationDemo fixed    (pinned algorithm, signature, exp, iss, aud)
 *
 * Written with JDK classes only so every step is visible. Production code uses a maintained library
 * (Spring Security's NimbusJwtDecoder), which performs these same checks.
 */
public class JwtValidationDemo {

    static final byte[] SECRET = "demo-only-hs256-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8);
    static final String ISSUER = "https://auth.demo.local";
    static final String AUDIENCE = "orders-api";

    public static void main(String[] args) throws Exception {
        boolean fixed = args.length > 0 && args[0].equals("fixed");
        long now = Instant.now().getEpochSecond();
        System.out.println("mode=" + (fixed ? "fixed" : "broken"));

        String valid = token("HS256", claims("alice", "USER", ISSUER, AUDIENCE, now + 900), true);
        String[] parts = valid.split("\\.");

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("valid token (alice, USER)      ", valid);
        // payload rewritten to ADMIN, original signature kept
        tokens.put("payload edited to role=ADMIN   ",
                parts[0] + "." + b64(claims("alice", "ADMIN", ISSUER, AUDIENCE, now + 900)) + "." + parts[2]);
        // header says "no signature", signature part left empty
        tokens.put("alg=none, no signature         ", token("none", claims("mallory", "ADMIN", ISSUER, AUDIENCE, now + 900), false));
        tokens.put("expired one hour ago           ", token("HS256", claims("alice", "USER", ISSUER, AUDIENCE, now - 3600), true));
        // genuinely signed by the same issuer, but issued for a different API
        tokens.put("issued for audience billing-api", token("HS256", claims("alice", "USER", ISSUER, "billing-api", now + 900), true));

        int wronglyAccepted = 0;
        boolean first = true;
        for (Map.Entry<String, String> entry : tokens.entrySet()) {
            String verdict = fixed ? validateFixed(entry.getValue(), now) : validateBroken(entry.getValue());
            System.out.printf("%s -> %s%n", entry.getKey(), verdict);
            if (!first && verdict.startsWith("ACCEPTED")) {
                wronglyAccepted++;
            }
            first = false;
        }
        System.out.println(wronglyAccepted == 0
                ? "RESULT: CORRECT (only the genuine, unexpired token for this API is accepted)"
                : "RESULT: WRONG (" + wronglyAccepted + " forged or unusable tokens were accepted)");
    }

    /** BUG: Base64-decoding is not verification. Whatever the payload says is believed. */
    private static String validateBroken(String token) {
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        return "ACCEPTED as sub=" + claim(payload, "sub") + " role=" + claim(payload, "role");
    }

    private static String validateFixed(String token, long now) throws Exception {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return "REJECTED: malformed";
        }
        String header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        // 1. The server decides the algorithm. Never let the token's header choose how it is verified.
        if (!"HS256".equals(claim(header, "alg"))) {
            return "REJECTED: algorithm '" + claim(header, "alg") + "' is not the configured HS256";
        }
        // 2. Signature over the exact bytes "header.payload", compared in constant time
        byte[] expected = hmac(parts[0] + "." + parts[1]);
        if (!MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2]))) {
            return "REJECTED: signature does not match";
        }
        // Only now is the payload trustworthy enough to read.
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        if (Long.parseLong(claim(payload, "exp")) <= now) {
            return "REJECTED: expired";
        }
        if (!ISSUER.equals(claim(payload, "iss"))) {
            return "REJECTED: unknown issuer";
        }
        if (!AUDIENCE.equals(claim(payload, "aud"))) {
            return "REJECTED: audience '" + claim(payload, "aud") + "' is not " + AUDIENCE;
        }
        return "ACCEPTED as sub=" + claim(payload, "sub") + " role=" + claim(payload, "role");
    }

    private static String token(String algorithm, String payload, boolean signed) throws Exception {
        String signingInput = b64("{\"alg\":\"" + algorithm + "\",\"typ\":\"JWT\"}") + "." + b64(payload);
        return signingInput + "." + (signed ? Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(signingInput)) : "");
    }

    private static String claims(String subject, String role, String issuer, String audience, long expiresAt) {
        return String.format("{\"sub\":\"%s\",\"role\":\"%s\",\"iss\":\"%s\",\"aud\":\"%s\",\"exp\":%d}",
                subject, role, issuer, audience, expiresAt);
    }

    private static byte[] hmac(String content) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        return mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Minimal claim extraction for the flat demo JSON; real code uses a JSON parser. */
    private static String claim(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\":\"?([^\",}]*)").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }
}
