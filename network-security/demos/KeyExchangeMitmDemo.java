import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Demo 4 - Key exchange: Diffie-Hellman stops eavesdroppers, but not an attacker who
 * can replace messages - unless the public keys are authenticated.
 *
 * Alice and Bob each create a one-time X25519 key pair, swap public keys and derive the
 * same secret. Mallory sits on the path and swaps in her own public key in both directions.
 *
 * Run broken:  java -cp out KeyExchangeMitmDemo broken   (public keys accepted as they arrive)
 * Run fixed:   java -cp out KeyExchangeMitmDemo fixed    (Bob signs his key; Alice knows Bob's identity key)
 *
 * No sockets: the "network" is Mallory handing values from one side to the other.
 */
public class KeyExchangeMitmDemo {

    public static void main(String[] args) throws Exception {
        boolean fixed = args.length > 0 && args[0].equals("fixed");
        System.out.println("mode=" + (fixed ? "fixed" : "broken"));

        KeyPairGenerator x25519 = KeyPairGenerator.getInstance("X25519");
        KeyPair alice = x25519.generateKeyPair();
        KeyPair bob = x25519.generateKeyPair();
        KeyPair mallory = x25519.generateKeyPair();

        // Bob's long-term identity. Alice obtained the public half in advance through a channel
        // Mallory does not control - in TLS that role is played by the certificate and its CA.
        KeyPair bobIdentity = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        // --- on the wire: Bob -> Alice is intercepted, Mallory forwards her own key instead ---
        byte[] bobSignature = sign(bobIdentity.getPrivate(), bob.getPublic());
        PublicKey keyAliceReceives = mallory.getPublic();

        if (fixed && !verify(bobIdentity.getPublic(), keyAliceReceives, bobSignature)) {
            System.out.println("alice: signature on the received key does not match Bob's identity key -> abort");
            // Without interference the same check passes and both sides share one key:
            boolean honestOk = verify(bobIdentity.getPublic(), bob.getPublic(), bobSignature);
            byte[] aliceKey = deriveKey(alice.getPrivate(), bob.getPublic());
            byte[] bobKey = deriveKey(bob.getPrivate(), alice.getPublic());
            System.out.println("untampered exchange: signature valid=" + honestOk
                    + ", alice and bob derive the same key=" + MessageDigest.isEqual(aliceKey, bobKey));
            System.out.println("RESULT: CORRECT (key substitution detected, nothing was sent)");
            return;
        }

        // Unauthenticated exchange: each side unknowingly agrees on a key with Mallory.
        byte[] aliceKey = deriveKey(alice.getPrivate(), keyAliceReceives);
        byte[] bobKey = deriveKey(bob.getPrivate(), mallory.getPublic());
        byte[] malloryWithAlice = deriveKey(mallory.getPrivate(), alice.getPublic());
        byte[] malloryWithBob = deriveKey(mallory.getPrivate(), bob.getPublic());
        System.out.printf("alice's session key: %s...%n", HexFormat.of().formatHex(aliceKey, 0, 8));
        System.out.printf("bob's session key:   %s...%n", HexFormat.of().formatHex(bobKey, 0, 8));
        System.out.println("alice and bob share a key: " + MessageDigest.isEqual(aliceKey, bobKey));

        byte[] nonce = new byte[12]; // fixed nonce is acceptable only because each key encrypts one message here
        byte[] fromAlice = aes(Cipher.ENCRYPT_MODE, aliceKey, nonce,
                "wire 5000 USD to account 1234".getBytes(StandardCharsets.UTF_8));
        String readByMallory = new String(aes(Cipher.DECRYPT_MODE, malloryWithAlice, nonce, fromAlice), StandardCharsets.UTF_8);
        byte[] toBob = aes(Cipher.ENCRYPT_MODE, malloryWithBob, nonce,
                readByMallory.replace("1234", "6666").getBytes(StandardCharsets.UTF_8));
        String readByBob = new String(aes(Cipher.DECRYPT_MODE, bobKey, nonce, toBob), StandardCharsets.UTF_8);

        System.out.println("mallory decrypts:    " + readByMallory);
        System.out.println("bob decrypts:        " + readByBob + "   (authentication tag valid)");
        System.out.println("RESULT: WRONG (strong encryption on both legs, with the attacker in the middle)");
    }

    /** X25519 agreement, then SHA-256 to turn the shared secret into a 256-bit AES key. */
    private static byte[] deriveKey(PrivateKey mine, PublicKey theirs) throws Exception {
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(mine);
        agreement.doPhase(theirs, true);
        return MessageDigest.getInstance("SHA-256").digest(agreement.generateSecret());
    }

    private static byte[] sign(PrivateKey identity, PublicKey ephemeral) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(identity);
        signature.update(ephemeral.getEncoded());
        return signature.sign();
    }

    private static boolean verify(PublicKey identity, PublicKey ephemeral, byte[] signatureBytes) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initVerify(identity);
        signature.update(ephemeral.getEncoded());
        return signature.verify(signatureBytes);
    }

    private static byte[] aes(int mode, byte[] key, byte[] nonce, byte[] input) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return cipher.doFinal(input);
    }
}
