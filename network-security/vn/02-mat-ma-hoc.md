# 02 — Mật mã học

Mục tiêu: sau bài này bạn biết năm khối xây dựng của mật mã (hash, MAC, mã hoá đối xứng, mã hoá bất đối xứng và chữ
ký số, trao đổi khoá), mỗi khối bảo đảm điều gì và **không** bảo đảm điều gì. Bài 3 ghép chúng lại thành TLS.

Bạn không cần biết toán bên trong. Bạn cần biết chọn đúng khối cho đúng việc, vì hầu hết lỗi mật mã trong thực tế
là dùng sai khối chứ không phải thuật toán bị phá.

## 1. Bản đồ

| Cần | Khối | Khoá | Thuật toán nên dùng |
|-----|------|------|---------------------|
| "Dấu vân tay" của dữ liệu | Hash | Không | SHA-256, SHA-3 |
| Biết dữ liệu không bị sửa **và** đến từ người có khoá | MAC | Một khoá chung | HMAC-SHA256 |
| Giữ bí mật (và toàn vẹn) | Mã hoá đối xứng có xác thực (AEAD) | Một khoá chung | AES-GCM, ChaCha20-Poly1305 |
| Chứng minh ai đã ký, ai cũng kiểm tra được | Chữ ký số | Cặp khoá riêng/công khai | ECDSA P-256, Ed25519, RSA-PSS / RSA PKCS#1 v1.5 |
| Hai bên chưa có gì chung tạo ra khoá chung | Trao đổi khoá | Cặp khoá dùng một lần | X25519 (ECDH) |

Hai quy tắc đứng trên tất cả:

- **Không tự thiết kế thuật toán hay giao thức mật mã.** Dùng thư viện chuẩn và giao thức chuẩn (TLS).
- **An toàn nằm ở khoá, không nằm ở việc giấu thuật toán.** Thuật toán là công khai; kẻ tấn công luôn biết bạn dùng gì.

## 2. Hash

Hàm hash biến dữ liệu dài bất kỳ thành một giá trị độ dài cố định.

```bash
printf 'hello' | openssl dgst -sha256
printf 'hellp' | openssl dgst -sha256
```

```
SHA2-256(stdin)= 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
SHA2-256(stdin)= fdd7585e08c4e2afd71dcabdb4636c89d557a3f42db9e2040c8bbd1708aa4ce7
```

Đổi một ký tự, kết quả đổi hoàn toàn. Tính chất của một hàm hash mật mã:

- **Một chiều**: từ hash không tính ngược ra dữ liệu.
- **Chống trùng**: không tìm được hai dữ liệu khác nhau có cùng hash.
- **Ai cũng tính được**: không có khoá.

MD5 và SHA-1 đã bị phá tính chống trùng; không dùng cho mục đích bảo mật.

### Hash không chống được kẻ sửa gói tin

Tính chất thứ ba chính là chỗ người mới hay nhầm. [IntegrityHashVsHmacDemo.java](../demos/IntegrityHashVsHmacDemo.java):
bên gửi kèm `SHA-256(message)` để bên nhận "kiểm tra toàn vẹn".

```
mode=broken tag=SHA-256(message)
sender    : "transfer 100 USD to alice" tag=d428fe931a1be829...
on the wire: "transfer 9900 USD to mallory" tag=d9897b9608472867...
receiver accepts the packet: true
RESULT: WRONG (forged message accepted: a hash proves nothing about who computed it)
```

Kẻ trên đường truyền sửa nội dung rồi **tự tính lại hash**. Bên nhận tính hash của nội dung mới, thấy khớp, chấp nhận.
Hash chỉ phát hiện lỗi ngẫu nhiên (hoặc sửa đổi khi bản hash được lấy qua một kênh khác đáng tin, như hash file tải
về đăng trên trang HTTPS).

## 3. MAC: toàn vẹn có khoá

MAC (Message Authentication Code) là "hash có khoá": chỉ ai có khoá chung mới tính được giá trị đúng.

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

Kẻ tấn công vẫn sửa được nội dung nhưng không tạo được tag hợp lệ.

Ba lưu ý khi dùng MAC:

- **So sánh tag trong thời gian hằng** (`MessageDigest.isEqual`). So sánh thường dừng ở byte sai đầu tiên, và thời
  gian phản hồi để lộ số byte đã đúng.
- **Không tự chế `SHA-256(key + message)`.** Cấu trúc này có lỗ hổng (length extension). HMAC tồn tại để tránh nó.
- MAC không chống **phát lại** (replay): gói hợp lệ bắt được hôm qua hôm nay gửi lại vẫn hợp lệ. Giao thức phải thêm
  số thứ tự, nonce hoặc thời gian vào phần được bảo vệ.

MAC dùng khoá chung nên không phân biệt được ai trong hai bên đã tạo tag. Muốn chứng minh với bên thứ ba thì cần chữ
ký số (mục 5).

## 4. Mã hoá đối xứng

Một khoá cho cả mã hoá và giải mã. Nhanh, dùng để mã hoá toàn bộ dữ liệu thật. Chuẩn hiện nay là AES với khoá 128
hoặc 256 bit.

AES chỉ mã hoá từng khối 16 byte. **Chế độ** (mode) quyết định cách ghép nhiều khối, và đây là nơi dễ sai nhất.

### ECB: mã hoá mà vẫn lộ

[EncryptionEcbVsGcmDemo.java](../demos/EncryptionEcbVsGcmDemo.java) mã hoá ba khối, hai khối đầu giống hệt nhau.

```
mode=broken cipher=AES/ECB/NoPadding
plaintext blocks:  PAY 100 TO ALICE | PAY 100 TO ALICE | PAY 999 TO BOB..
ciphertext blocks: ae397c23f8b7f1bd.. | ae397c23f8b7f1bd.. | 27e913207e4254e5..
blocks 1 and 2 identical in ciphertext: true
attacker copies ciphertext block 3 over block 1, receiver decrypts:
                   PAY 999 TO BOB.. | PAY 100 TO ALICE | PAY 999 TO BOB..
RESULT: WRONG (pattern visible, and a modified ciphertext decrypts without any error)
```

Hai vấn đề, cả hai đều nghiêm trọng:

1. **Lộ mẫu.** ECB mã hoá mỗi khối độc lập, nên khối giống nhau cho bản mã giống nhau. Kẻ nghe lén không đọc được
   nội dung nhưng biết "hai lệnh đầu y hệt nhau".
2. **Không có toàn vẹn.** Kẻ tấn công không cần khoá vẫn cắt dán được các khối bản mã. Bên nhận giải mã ra một lệnh
   khác mà không có lỗi nào. Mã hoá **không** tự động chống sửa đổi.

Trong Java, `Cipher.getInstance("AES")` không ghi chế độ sẽ mặc định là ECB. Luôn ghi đầy đủ.

### GCM: mã hoá có xác thực (AEAD)

AEAD ghép mã hoá và MAC thành một thao tác. Ngoài khoá, nó cần một **nonce**: giá trị không được lặp lại với cùng khoá.

```java
byte[] nonce = new byte[12];
new SecureRandom().nextBytes(nonce);                 // không bao giờ lặp lại với cùng một khoá
Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
byte[] ciphertext = cipher.doFinal(plaintext);       // bản mã || tag 16 byte
```

```
mode=fixed cipher=AES/GCM/NoPadding
ciphertext blocks: 72d7daaf64d3a7c1.. | 6e6a281d75b8e344.. | 9a5414ab0a007c97..
blocks 1 and 2 identical in ciphertext: false
same plaintext encrypted twice gives the same bytes: false
attacker flips one bit, receiver decrypts: AEADBadTagException: Tag mismatch
RESULT: CORRECT (no pattern, and any modification is detected)
```

| | ECB | CBC (không MAC) | GCM / ChaCha20-Poly1305 |
|---|---|---|---|
| Che mẫu | Không | Có | Có |
| Phát hiện sửa đổi | Không | Không | **Có** |
| Dùng cho code mới | Không bao giờ | Không | **Có** |

Lưu ý với GCM: dùng lại một nonce với cùng khoá phá vỡ cả bí mật lẫn toàn vẹn. Nonce không cần giữ bí mật (gửi kèm
bản mã), chỉ cần không lặp.

Mã hoá đối xứng còn một bài toán chưa giải: **hai bên lấy khoá chung ở đâu** khi chưa từng gặp nhau? Đó là việc của
hai mục tiếp theo.

## 5. Mật mã bất đối xứng và chữ ký số

Mỗi bên có một cặp khoá: **khoá riêng** (giữ kín tuyệt đối) và **khoá công khai** (phát cho ai cũng được).

| Thao tác | Dùng khoá | Bảo đảm |
|----------|-----------|---------|
| Ký | Khoá riêng của người ký | Chỉ chủ khoá riêng tạo được chữ ký |
| Kiểm tra chữ ký | Khoá công khai của người ký | Ai cũng kiểm tra được; nội dung không bị sửa |

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

| | MAC | Chữ ký số |
|---|---|---|
| Khoá | Một khoá chung | Khoá riêng để ký, khoá công khai để kiểm tra |
| Ai kiểm tra được | Người có khoá chung | Bất kỳ ai |
| Chứng minh được ai tạo | Không (cả hai bên đều tạo được) | Có |
| Tốc độ | Rất nhanh | Chậm hơn nhiều |

Chữ ký số là nền của chứng chỉ (bài 4), JWT ký bằng RS256/ES256 (bài 5), chữ ký trong bắt tay TLS (bài 3), SSH key và ký phần mềm.

Phép toán bất đối xứng chậm, nên không ai dùng nó để mã hoá dữ liệu lớn. Thực tế luôn là **lai**: bất đối xứng để
xác thực và thống nhất khoá, đối xứng để mã hoá dữ liệu.

### RSA và ECC

Hai họ thuật toán bất đối xứng đang dùng. RSA dựa trên độ khó phân tích thừa số nguyên tố; ECC (mật mã đường cong
elliptic) dựa trên bài toán logarit rời rạc trên đường cong, cho cùng độ an toàn với khoá ngắn hơn nhiều.

| | RSA | ECC |
|---|---|---|
| Kích thước khoá cho ~128 bit an toàn | 3072 bit (2048 bit ≈ 112 bit, vẫn được chấp nhận) | 256 bit (P-256, Curve25519) |
| Ký | RSA PKCS#1 v1.5 (`RS256`), RSA-PSS (`PS256`) | ECDSA (`ES256`), EdDSA (`Ed25519`) |
| Mã hoá / trao đổi khoá | RSA-OAEP; "RSA key exchange" của TLS 1.2 (đã bỏ ở TLS 1.3, không có forward secrecy) | ECDH / X25519 |
| Điểm mạnh | Kiểm tra chữ ký rất nhanh; tương thích rộng nhất | Khoá và chữ ký nhỏ; ký nhanh |
| Bẫy thường gặp | Padding PKCS#1 v1.5 cho **mã hoá** (tấn công Bleichenbacher): dùng OAEP | ECDSA cần số ngẫu nhiên tốt cho mỗi chữ ký: lặp lại là lộ khoá riêng |

Số đo trên máy viết tài liệu (JDK 21.0.5, provider mặc định, dữ liệu 256 byte, trung bình 1000 lần sau khi khởi động; dấu phẩy thập phân do locale của máy):

```
RSA      2048  pubKey= 294 B  sig=256 B  sign=  968,1 us  verify=  40,9 us
RSA      3072  pubKey= 422 B  sig=384 B  sign= 2791,2 us  verify=  75,3 us
EC       256   pubKey=  91 B  sig= 70 B  sign=  149,3 us  verify= 440,5 us
Ed25519        pubKey=  44 B  sig= 64 B  sign=  466,4 us  verify= 460,7 us
```

Đọc cho đúng bối cảnh: RSA **ký chậm, kiểm tra nhanh**; ECDSA ngược lại. Với một resource server kiểm tra hàng nghìn
JWT mỗi giây, RS256 tốn ít CPU hơn ES256 ở phía kiểm tra; với server TLS phải ký trong mỗi lần bắt tay, chứng chỉ
ECDSA rẻ hơn RSA và nhỏ hơn trên dây. Con số tuyệt đối phụ thuộc phần cứng và provider (Ed25519 trong JDK là bản cài
đặt Java thuần, nên ở đây chậm hơn ECDSA).

Lựa chọn mặc định hợp lý hiện nay: chứng chỉ TLS ECDSA P-256 (hoặc RSA 2048 khi cần tương thích cũ); JWT `RS256` hoặc
`ES256` tuỳ issuer; trao đổi khoá X25519. Máy tính lượng tử đủ lớn sẽ phá cả RSA lẫn ECC; đó là lý do các trình duyệt
và server mới đã bắt đầu dùng trao đổi khoá lai `X25519MLKEM768` (bạn sẽ thấy nó trong output `openssl s_client` ở bài 3).

Câu hỏi còn lại: làm sao biết khoá công khai này **đúng là của** người mình muốn nói chuyện? Mục 6 cho thấy điều gì
xảy ra khi bỏ qua câu hỏi đó.

## 6. Trao đổi khoá Diffie–Hellman

Hai bên mỗi bên tạo một cặp khoá dùng một lần, gửi cho nhau phần công khai, rồi mỗi bên kết hợp khoá riêng của mình
với khoá công khai của bên kia. Cả hai ra **cùng một bí mật**, còn kẻ nghe lén chỉ thấy hai khoá công khai thì không
tính ra được.

```
Alice: tạo (a, A)                         Bob: tạo (b, B)
        ──────────── A ───────────────▶
        ◀─────────── B ────────────────
bí mật = f(a, B)            =             bí mật = f(b, A)
```

```java
KeyAgreement agreement = KeyAgreement.getInstance("X25519");
agreement.init(myPrivateKey);
agreement.doPhase(theirPublicKey, true);
byte[] sharedSecret = agreement.generateSecret();
```

Vì cặp khoá chỉ dùng một lần rồi xoá, lộ khoá dài hạn của server về sau cũng không giải mã được các phiên đã ghi lại.
Tính chất này gọi là **forward secrecy**.

### Điểm yếu: kẻ thay được gói tin

[KeyExchangeMitmDemo.java](../demos/KeyExchangeMitmDemo.java): Mallory đứng giữa và thay khoá công khai ở cả hai chiều
bằng khoá của mình.

```
mode=broken
alice's session key: f9653c133a696af3...
bob's session key:   c17f22b0df572c8b...
alice and bob share a key: false
mallory decrypts:    wire 5000 USD to account 1234
bob decrypts:        wire 5000 USD to account 6666   (authentication tag valid)
RESULT: WRONG (strong encryption on both legs, with the attacker in the middle)
```

Alice thống nhất khoá với Mallory, Bob cũng vậy. Cả hai chặng đều mã hoá bằng AES-GCM, tag đều hợp lệ, và Mallory đọc
rồi sửa mọi thứ. Mã hoá mạnh đến đâu cũng vô nghĩa nếu không biết mình đang mã hoá **cho ai**.

### Fix: xác thực khoá công khai

Bob có thêm một cặp khoá định danh dài hạn và **ký** khoá công khai dùng một lần của mình. Alice đã biết trước khoá
định danh công khai của Bob qua một kênh Mallory không kiểm soát.

```
mode=fixed
alice: signature on the received key does not match Bob's identity key -> abort
untampered exchange: signature valid=true, alice and bob derive the same key=true
RESULT: CORRECT (key substitution detected, nothing was sent)
```

Mallory vẫn thay được khoá, nhưng không tạo được chữ ký của Bob. Đây chính là hình dạng của TLS: trao đổi khoá dùng
một lần, cộng với chữ ký từ khoá dài hạn của server. Phần "Alice biết trước khoá định danh của Bob bằng cách nào" được
giải bằng chứng chỉ và CA (bài 4).

Demo đã đơn giản hoá: giao thức thật ký toàn bộ nội dung bắt tay, không chỉ một khoá.

## 7. Mật khẩu là trường hợp riêng

Hash nhanh như SHA-256 **không** dùng để lưu mật khẩu: kẻ lấy được database thử được hàng tỷ mật khẩu mỗi giây. Lưu
mật khẩu cần hàm chậm có chủ đích và có salt: Argon2id, bcrypt, scrypt. Chi tiết nằm trong tài liệu Spring Security
của kho này ([bài 3](../../springboot/security/vn/03-authentication-xac-thuc.md)).

## 8. Số ngẫu nhiên

Khoá, nonce, token đều phải lấy từ bộ sinh ngẫu nhiên mật mã: `SecureRandom` trong Java, `openssl rand -hex 32` trên
dòng lệnh. `java.util.Random` và `Math.random()` đoán trước được; không dùng cho bất cứ thứ gì liên quan bảo mật.

## 9. Tự kiểm tra

1. Gửi file kèm SHA-256 của nó trong cùng một kênh không mã hoá có chống được kẻ sửa file không?
2. Một hệ thống "mã hoá bằng AES-256" có thể vẫn không an toàn vì những lý do nào?
3. MAC và chữ ký số khác nhau ở điểm cốt lõi nào?
4. Diffie–Hellman không xác thực chống được loại kẻ tấn công nào, và thua loại nào?

Đáp án ngắn: (1) Không; kẻ sửa file tính lại hash. (2) Dùng ECB (lộ mẫu), không có xác thực (bản mã bị sửa mà không
biết), lặp nonce, khoá sinh từ nguồn không ngẫu nhiên hoặc để lộ. (3) MAC dùng một khoá chung, chỉ người có khoá kiểm
tra được và không chứng minh được ai tạo; chữ ký dùng khoá riêng để ký, ai cũng kiểm tra được bằng khoá công khai.
(4) Chống được kẻ thụ động chỉ nghe; thua kẻ chủ động thay được khoá công khai.

Bài tiếp theo: [03 — TLS](03-tls.md).
