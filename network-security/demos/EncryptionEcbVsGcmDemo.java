import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Demo 3 - Encryption modes: "encrypted with AES" says little until you know the mode.
 *
 * The plaintext is three 16-byte blocks; the first two are identical.
 *
 * Run broken:  java -cp out EncryptionEcbVsGcmDemo broken   (AES/ECB: leaks patterns, accepts tampering)
 * Run fixed:   java -cp out EncryptionEcbVsGcmDemo fixed    (AES/GCM: random nonce, authenticated)
 */
public class EncryptionEcbVsGcmDemo {

    static final String PLAINTEXT = "PAY 100 TO ALICE" + "PAY 100 TO ALICE" + "PAY 999 TO BOB..";
    static final int BLOCK = 16;

    public static void main(String[] args) throws Exception {
        boolean fixed = args.length > 0 && args[0].equals("fixed");
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        SecretKey key = generator.generateKey();
        System.out.printf("mode=%s cipher=%s%n", fixed ? "fixed" : "broken", fixed ? "AES/GCM/NoPadding" : "AES/ECB/NoPadding");
        System.out.println("plaintext blocks:  " + String.join(" | ", PLAINTEXT.substring(0, 16),
                PLAINTEXT.substring(16, 32), PLAINTEXT.substring(32)));
        if (fixed) {
            runGcm(key);
        } else {
            runEcb(key);
        }
    }

    private static void runEcb(SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] ciphertext = cipher.doFinal(PLAINTEXT.getBytes(StandardCharsets.UTF_8));
        printBlocks("ciphertext blocks: ", ciphertext);
        boolean patternLeaks = Arrays.equals(block(ciphertext, 0), block(ciphertext, 1));
        System.out.println("blocks 1 and 2 identical in ciphertext: " + patternLeaks);

        // The attacker cannot read anything, but can cut and paste blocks: copy block 3 over block 1.
        byte[] tampered = ciphertext.clone();
        System.arraycopy(ciphertext, 2 * BLOCK, tampered, 0, BLOCK);
        cipher.init(Cipher.DECRYPT_MODE, key);
        String decrypted = new String(cipher.doFinal(tampered), StandardCharsets.UTF_8);
        System.out.println("attacker copies ciphertext block 3 over block 1, receiver decrypts:");
        System.out.println("                   " + String.join(" | ", decrypted.substring(0, 16),
                decrypted.substring(16, 32), decrypted.substring(32)));
        System.out.println("RESULT: WRONG (pattern visible, and a modified ciphertext decrypts without any error)");
    }

    private static void runGcm(SecretKey key) throws Exception {
        byte[] first = encryptGcm(key);
        byte[] second = encryptGcm(key);
        printBlocks("ciphertext blocks: ", Arrays.copyOfRange(first, 12, 12 + 3 * BLOCK));
        System.out.println("blocks 1 and 2 identical in ciphertext: "
                + Arrays.equals(Arrays.copyOfRange(first, 12, 28), Arrays.copyOfRange(first, 28, 44)));
        System.out.println("same plaintext encrypted twice gives the same bytes: " + Arrays.equals(first, second));

        byte[] tampered = first.clone();
        tampered[12] ^= 0x01; // flip a single bit of the ciphertext
        try {
            decryptGcm(key, tampered);
            System.out.println("RESULT: WRONG (tampered ciphertext was accepted)");
        } catch (AEADBadTagException e) {
            System.out.println("attacker flips one bit, receiver decrypts: AEADBadTagException: " + e.getMessage());
            System.out.println("untampered message decrypts to: " + decryptGcm(key, first));
            System.out.println("RESULT: CORRECT (no pattern, and any modification is detected)");
        }
    }

    /** Output layout: 12-byte nonce || ciphertext || 16-byte authentication tag. */
    private static byte[] encryptGcm(SecretKey key) throws Exception {
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce); // must never repeat for the same key
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(PLAINTEXT.getBytes(StandardCharsets.UTF_8));
        byte[] message = new byte[nonce.length + ciphertext.length];
        System.arraycopy(nonce, 0, message, 0, nonce.length);
        System.arraycopy(ciphertext, 0, message, nonce.length, ciphertext.length);
        return message;
    }

    private static String decryptGcm(SecretKey key, byte[] message) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, message, 0, 12));
        return new String(cipher.doFinal(message, 12, message.length - 12), StandardCharsets.UTF_8);
    }

    private static byte[] block(byte[] data, int index) {
        return Arrays.copyOfRange(data, index * BLOCK, (index + 1) * BLOCK);
    }

    private static void printBlocks(String label, byte[] data) {
        StringBuilder line = new StringBuilder(label);
        for (int i = 0; i < data.length / BLOCK; i++) {
            line.append(i == 0 ? "" : " | ").append(HexFormat.of().formatHex(block(data, i), 0, 8)).append("..");
        }
        System.out.println(line);
    }
}
