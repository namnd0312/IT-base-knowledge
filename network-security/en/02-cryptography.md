# 02 — Cryptography

Goal: after this lesson you know the five building blocks of cryptography (hashes, MACs, symmetric encryption,
asymmetric cryptography with digital signatures, key exchange), what each guarantees and what it does **not**. Lesson
3 assembles them into TLS.

You do not need the mathematics inside. You need to pick the right block for the job, because most cryptographic
failures in practice come from using the wrong block, not from a broken algorithm.

## 1. The map

| Need | Block | Key | Recommended algorithms |
|------|-------|-----|------------------------|
| A "fingerprint" of data | Hash | None | SHA-256, SHA-3 |
| Know the data was not modified **and** came from a key holder | MAC | One shared key | HMAC-SHA256 |
| Keep data secret (and intact) | Authenticated symmetric encryption (AEAD) | One shared key | AES-GCM, ChaCha20-Poly1305 |
| Prove who signed, verifiable by anyone | Digital signature | Private/public key pair | ECDSA P-256, Ed25519, RSA-PSS / RSA PKCS#1 v1.5 |
| Two parties with nothing in common create a shared key | Key exchange | One-time key pairs | X25519 (ECDH) |

Two rules above all others:

- **Do not design your own cryptographic algorithm or protocol.** Use standard libraries and standard protocols (TLS).
- **Security rests in the key, not in hiding the algorithm.** Algorithms are public; the attacker always knows what
  you use.

## 2. Hashes

A hash function turns data of any length into a fixed-length value.

```bash
printf 'hello' | openssl dgst -sha256
printf 'hellp' | openssl dgst -sha256
```

```
SHA2-256(stdin)= 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
SHA2-256(stdin)= fdd7585e08c4e2afd71dcabdb4636c89d557a3f42db9e2040c8bbd1708aa4ce7
```

Change one character and the result changes completely. Properties of a cryptographic hash:

- **One-way**: the data cannot be computed back from the hash.
- **Collision-resistant**: you cannot find two different inputs with the same hash.
- **Anyone can compute it**: there is no key.

MD5 and SHA-1 have lost collision resistance; do not use them for security purposes.

### A hash does not stop someone who modifies packets

The third property is exactly where beginners go wrong. [IntegrityHashVsHmacDemo.java](../demos/IntegrityHashVsHmacDemo.java):
the sender attaches `SHA-256(message)` so the receiver can "check integrity".

```
mode=broken tag=SHA-256(message)
sender    : "transfer 100 USD to alice" tag=d428fe931a1be829...
on the wire: "transfer 9900 USD to mallory" tag=d9897b9608472867...
receiver accepts the packet: true
RESULT: WRONG (forged message accepted: a hash proves nothing about who computed it)
```

The on-path attacker changes the content and **recomputes the hash**. The receiver hashes the new content, sees a
match, and accepts. A hash only detects accidental errors (or modification when the hash itself arrives through a
separate trusted channel, such as a download checksum published on an HTTPS page).

## 3. MACs: integrity with a key

A MAC (Message Authentication Code) is a "keyed hash": only someone holding the shared key can compute the right value.

```java
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(key, "HmacSHA256"));
byte[] tag = mac.doFinal(message);
```

```
mode=fixed tag=HMAC-SHA256(key, message)
sender    : "transfer 100 USD to alice" tag=163a14907efd2795...
on the wire: "transfer 9900 USD to mallory" tag=1883831b2e1bc5da...
receiver accepts the packet: false
RESULT: CORRECT (forged message rejected: the tag cannot be produced without the key)
```

The attacker can still change the content but cannot produce a valid tag.

Three notes on using MACs:

- **Compare tags in constant time** (`MessageDigest.isEqual`). An ordinary comparison stops at the first wrong byte,
  and the response time leaks how many bytes were right.
- **Do not improvise `SHA-256(key + message)`.** That construction has a weakness (length extension). HMAC exists to
  avoid it.
- A MAC does not stop **replay**: a valid packet captured yesterday is still valid when resent today. The protocol
  must put a sequence number, nonce or timestamp inside the protected part.

A MAC uses a shared key, so it cannot tell which of the two parties produced the tag. Proving it to a third party
takes a digital signature (section 5).

## 4. Symmetric encryption

One key both encrypts and decrypts. It is fast and is what encrypts all the actual data. The current standard is AES
with a 128- or 256-bit key.

AES only encrypts single 16-byte blocks. The **mode** decides how many blocks are combined, and this is where things
most easily go wrong.

### ECB: encrypted and still leaking

[EncryptionEcbVsGcmDemo.java](../demos/EncryptionEcbVsGcmDemo.java) encrypts three blocks, the first two identical.

```
mode=broken cipher=AES/ECB/NoPadding
plaintext blocks:  PAY 100 TO ALICE | PAY 100 TO ALICE | PAY 999 TO BOB..
ciphertext blocks: ae397c23f8b7f1bd.. | ae397c23f8b7f1bd.. | 27e913207e4254e5..
blocks 1 and 2 identical in ciphertext: true
attacker copies ciphertext block 3 over block 1, receiver decrypts:
                   PAY 999 TO BOB.. | PAY 100 TO ALICE | PAY 999 TO BOB..
RESULT: WRONG (pattern visible, and a modified ciphertext decrypts without any error)
```

Two problems, both serious:

1. **Pattern leakage.** ECB encrypts each block independently, so equal blocks give equal ciphertext. The
   eavesdropper cannot read the content but learns "the first two orders are identical".
2. **No integrity.** Without the key, the attacker can still cut and paste ciphertext blocks. The receiver decrypts a
   different order with no error at all. Encryption does **not** automatically prevent modification.

In Java, `Cipher.getInstance("AES")` with no mode defaults to ECB. Always spell out the full transformation.

### GCM: authenticated encryption (AEAD)

AEAD combines encryption and a MAC in one operation. Besides the key it needs a **nonce**: a value that must never
repeat under the same key.

```java
byte[] nonce = new byte[12];
new SecureRandom().nextBytes(nonce);                 // must never repeat for the same key
Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
byte[] ciphertext = cipher.doFinal(plaintext);       // ciphertext || 16-byte tag
```

```
mode=fixed cipher=AES/GCM/NoPadding
ciphertext blocks: 72d7daaf64d3a7c1.. | 6e6a281d75b8e344.. | 9a5414ab0a007c97..
blocks 1 and 2 identical in ciphertext: false
same plaintext encrypted twice gives the same bytes: false
attacker flips one bit, receiver decrypts: AEADBadTagException: Tag mismatch
RESULT: CORRECT (no pattern, and any modification is detected)
```

| | ECB | CBC (without a MAC) | GCM / ChaCha20-Poly1305 |
|---|---|---|---|
| Hides patterns | No | Yes | Yes |
| Detects modification | No | No | **Yes** |
| For new code | Never | No | **Yes** |

A caveat for GCM: reusing a nonce under the same key breaks both confidentiality and integrity. The nonce need not be
secret (it travels with the ciphertext); it only must not repeat.

Symmetric encryption leaves one problem unsolved: **where do two parties get a shared key** when they have never met?
That is the job of the next two sections.

## 5. Asymmetric cryptography and digital signatures

Each party has a key pair: a **private key** (kept strictly secret) and a **public key** (handed to anyone).

| Operation | Key used | Guarantee |
|-----------|----------|-----------|
| Sign | The signer's private key | Only the private-key holder can create the signature |
| Verify | The signer's public key | Anyone can verify; the content was not modified |

```java
Signature signer = Signature.getInstance("Ed25519");
signer.initSign(privateKey);
signer.update(data);
byte[] signature = signer.sign();

Signature verifier = Signature.getInstance("Ed25519");
verifier.initVerify(publicKey);
verifier.update(data);
boolean valid = verifier.verify(signature);
```

| | MAC | Digital signature |
|---|---|---|
| Keys | One shared key | Private key signs, public key verifies |
| Who can verify | Holders of the shared key | Anyone |
| Proves who created it | No (either party could have) | Yes |
| Speed | Very fast | Much slower |

Digital signatures underpin certificates (lesson 4), JWTs signed with RS256/ES256 (lesson 5), the signature in the TLS
handshake (lesson 3), SSH keys and software signing.

Asymmetric operations are slow, so nobody uses them to encrypt bulk data. Practice is always **hybrid**: asymmetric
cryptography to authenticate and agree on a key, symmetric cryptography to encrypt the data.

### RSA and ECC

The two asymmetric families in use. RSA rests on the difficulty of factoring; ECC (elliptic-curve cryptography) rests
on the discrete logarithm problem on a curve and gives the same security with far shorter keys.

| | RSA | ECC |
|---|---|---|
| Key size for ~128-bit security | 3072 bits (2048 bits ≈ 112-bit, still accepted) | 256 bits (P-256, Curve25519) |
| Signing | RSA PKCS#1 v1.5 (`RS256`), RSA-PSS (`PS256`) | ECDSA (`ES256`), EdDSA (`Ed25519`) |
| Encryption / key exchange | RSA-OAEP; TLS 1.2 "RSA key exchange" (removed in TLS 1.3, no forward secrecy) | ECDH / X25519 |
| Strengths | Very fast verification; the widest compatibility | Small keys and signatures; fast signing |
| Common trap | PKCS#1 v1.5 padding for **encryption** (Bleichenbacher attacks): use OAEP | ECDSA needs good randomness per signature: a repeated value leaks the private key |

Measured on the machine this guide was written on (JDK 21.0.5, default provider, 256-byte input, mean of 1000 runs after warm-up):

```
RSA      2048  pubKey= 294 B  sig=256 B  sign=  968,1 us  verify=  40,9 us
RSA      3072  pubKey= 422 B  sig=384 B  sign= 2791,2 us  verify=  75,3 us
EC       256   pubKey=  91 B  sig= 70 B  sign=  149,3 us  verify= 440,5 us
Ed25519        pubKey=  44 B  sig= 64 B  sign=  466,4 us  verify= 460,7 us
```

(The decimal comma comes from the machine's locale.) Read it in context: RSA **signs slowly and verifies quickly**;
ECDSA is the reverse. For a resource server verifying thousands of JWTs per second, RS256 costs less CPU on the
verifying side than ES256; for a TLS server that signs in every handshake, an ECDSA certificate is cheaper than RSA and
smaller on the wire. Absolute numbers depend on hardware and provider (the JDK's Ed25519 is a pure-Java
implementation, hence slower than ECDSA here).

Sensible defaults today: ECDSA P-256 TLS certificates (or RSA 2048 when old clients must be supported); `RS256` or
`ES256` JWTs depending on the issuer; X25519 key exchange. A large enough quantum computer would break both RSA and
ECC; that is why recent browsers and servers have started using the hybrid key exchange `X25519MLKEM768` (you will see
it in the `openssl s_client` output in lesson 3).

The remaining question: how do you know this public key **really belongs to** the party you want to talk to? Section 6
shows what happens when that question is skipped.

## 6. Diffie–Hellman key exchange

Each side creates a one-time key pair, sends the public half to the other, and combines its own private key with the
other's public key. Both arrive at the **same secret**, while an eavesdropper who sees only the two public keys
cannot compute it.

```
Alice: creates (a, A)                     Bob: creates (b, B)
        ──────────── A ───────────────▶
        ◀─────────── B ────────────────
secret = f(a, B)            =             secret = f(b, A)
```

```java
KeyAgreement agreement = KeyAgreement.getInstance("X25519");
agreement.init(myPrivateKey);
agreement.doPhase(theirPublicKey, true);
byte[] sharedSecret = agreement.generateSecret();
```

Because the key pairs are used once and deleted, a later leak of the server's long-term key cannot decrypt recorded
sessions. This property is called **forward secrecy**.

### The weakness: an attacker who can replace packets

[KeyExchangeMitmDemo.java](../demos/KeyExchangeMitmDemo.java): Mallory sits in the middle and replaces the public key
in both directions with her own.

```
mode=broken
alice's session key: f9653c133a696af3...
bob's session key:   c17f22b0df572c8b...
alice and bob share a key: false
mallory decrypts:    wire 5000 USD to account 1234
bob decrypts:        wire 5000 USD to account 6666   (authentication tag valid)
RESULT: WRONG (strong encryption on both legs, with the attacker in the middle)
```

Alice agrees on a key with Mallory, and so does Bob. Both legs are encrypted with AES-GCM, both tags are valid, and
Mallory reads and rewrites everything. However strong the encryption, it is meaningless if you do not know **whom**
you are encrypting to.

### The fix: authenticate the public key

Bob additionally has a long-term identity key pair and **signs** his one-time public key. Alice already knows Bob's
public identity key through a channel Mallory does not control.

```
mode=fixed
alice: signature on the received key does not match Bob's identity key -> abort
untampered exchange: signature valid=true, alice and bob derive the same key=true
RESULT: CORRECT (key substitution detected, nothing was sent)
```

Mallory can still swap the key but cannot produce Bob's signature. This is the very shape of TLS: a one-time key
exchange plus a signature from the server's long-term key. The part "how does Alice know Bob's identity key in
advance" is solved by certificates and CAs (lesson 4).

The demo simplifies: real protocols sign the whole handshake transcript, not a single key.

## 7. Passwords are a special case

A fast hash such as SHA-256 is **not** for storing passwords: whoever obtains the database can try billions of
passwords per second. Password storage needs deliberately slow, salted functions: Argon2id, bcrypt, scrypt. Details
are in this repository's Spring Security guide ([lesson 3](../../springboot/security/en/03-authentication.md)).

## 8. Random numbers

Keys, nonces and tokens must all come from a cryptographic random generator: `SecureRandom` in Java,
`openssl rand -hex 32` on the command line. `java.util.Random` and `Math.random()` are predictable; do not use them
for anything security-related.

## 9. Self-check

1. Does sending a file together with its SHA-256 over the same unencrypted channel stop someone who modifies the file?
2. For which reasons can a system "encrypted with AES-256" still be insecure?
3. What is the core difference between a MAC and a digital signature?
4. Which kind of attacker does unauthenticated Diffie–Hellman resist, and which does it lose to?

Short answers: (1) No; whoever modifies the file recomputes the hash. (2) ECB mode (pattern leakage), no
authentication (ciphertext modified unnoticed), nonce reuse, keys generated from a non-random source or leaked. (3) A
MAC uses one shared key, only key holders can verify, and it cannot prove who created it; a signature is made with a
private key and anyone can verify it with the public key. (4) It resists a passive attacker who only listens; it
loses to an active attacker who can replace the public keys.

Next: [03 — TLS](03-tls.md).
