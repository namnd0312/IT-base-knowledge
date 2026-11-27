# 01 — Networking fundamentals: what your data passes through

Goal: know enough networking to answer precisely "when service A calls service B, at each hop who can see what, who
can change what, and which layer is responsible for protecting it". This lesson does not re-teach networking from
scratch; it picks the parts the TLS, PKI and authentication lessons rely on.

## 1. The OSI and TCP/IP models: where TLS sits

| OSI | TCP/IP | Protocols | Unit / address | Security note |
|-----|--------|-----------|----------------|---------------|
| 7 Application | Application | HTTP, gRPC, DNS, SMTP | URL, headers | User authentication, authorisation (lessons 5, 6) |
| 6 Presentation | ↑ | **TLS** (usually placed here, or "between 4 and 7") | | Encryption, integrity, server/client authentication (lesson 3) |
| 5 Session | ↑ | | | |
| 4 Transport | Transport | TCP, UDP, QUIC (over UDP) | Port | No encryption, no authentication |
| 3 Network | Internet | IP, ICMP | IP address | Source IPs can be spoofed; IPsec if encryption is needed at this layer |
| 2 Data link | Link | Ethernet, Wi-Fi, ARP | MAC | ARP is unauthenticated: easy to get in the middle on a LAN |
| 1 Physical | ↑ | Cable, radio | | |

```
┌──────────┬──────────┬──────────┬────────────────────────────────────────────┐
│ Ethernet │ IP       │ TCP      │ TLS record                                  │
│ MAC      │ source IP│ ports    │ ┌────────────────────────────────────────┐ │
│          │ dest IP  │          │ │ HTTP: GET /orders  Authorization: ...   │ │ ← only this part is encrypted
│          │          │          │ └────────────────────────────────────────┘ │
└──────────┴──────────┴──────────┴────────────────────────────────────────────┘
 changes per hop  visible end to end  visible        secret from every device in between
```

Three consequences to remember:

1. Layers 2–4 have **no encryption or authentication**. Every guarantee of confidentiality and identity comes from TLS
   (or SSH, IPsec) and from the application layer.
2. TLS hides the HTTP content (URL path, headers, body, cookies, tokens); it does **not** hide the IPs, ports, packet
   sizes and timing, or the domain name (the SNI field is sent in clear in the first message).
3. At every hop (Wi-Fi, switch, router, load balancer, proxy, service-mesh sidecar) there is a device that can read
   whatever is not encrypted. The safe assumption: **everything on the path can read and modify your packets**.

## 2. TCP and UDP

| | TCP | UDP |
|---|---|---|
| Connection | Yes (three-way handshake) | No |
| Guarantees | Complete, in order, retransmitted on loss | None |
| Used for | HTTP/1.1, HTTP/2, JDBC, gRPC | DNS, QUIC (HTTP/3), WireGuard, streaming |
| Security on top | TLS | DTLS, or QUIC (TLS 1.3 built in) |
| Spoofing the source IP | Hard: the SYN-ACK must be received | Easy: one packet is enough (the basis of amplification attacks) |

## 3. The three-way handshake, and the real cost of an HTTPS connection

```
Client                                   Server
  │ ── SYN (seq=x) ────────────────────────▶ │
  │ ◀── SYN-ACK (seq=y, ack=x+1) ─────────── │      1 RTT: TCP
  │ ── ACK (ack=y+1) ──────────────────────▶ │
  │ ── TLS ClientHello ────────────────────▶ │
  │ ◀── ServerHello ... Finished ─────────── │      +1 RTT: TLS 1.3 (TLS 1.2: +2 RTT)
  │ ── Finished + HTTP request ────────────▶ │
  │ ◀── HTTP response ────────────────────── │      +1 RTT: the actual request
```

- The TCP handshake only synchronises sequence numbers. "Connection open" does **not** mean anyone authenticated anyone.
- A port answering SYN-ACK is *open*, RST is *closed*, silence is *filtered* (a firewall drops it): that is how port
  scanning works.
- The server keeps state for each SYN awaiting its ACK; sending masses of SYNs and walking away is a SYN flood (Linux
  defends with SYN cookies).

What it means for a Java server: a fresh HTTPS connection costs **2 RTTs + asymmetric cryptography** before the first
data byte can be sent. Therefore:

- Use connection pooling and keep-alive (`HttpClient`, `RestClient`, JDBC pools) instead of a new connection per request.
- TLS session resumption cuts the cost of handshaking again (lesson 3).
- Set a **connect timeout and a read timeout** on every outbound call. A connection without a timeout is a thread
  that can be held forever.

## 4. DNS

DNS turns names into IPs. Almost every connection starts with a DNS query.

```bash
dig +noall +answer example.com A
```

```
example.com.		205	IN	A	104.20.23.154
example.com.		205	IN	A	172.66.147.243
```

Classic DNS runs over UDP 53, **unencrypted and unauthenticated**: people on the path see which name you ask for, and
whoever can forge an answer sends you to their IP. DNSSEC (signed records) and DoT/DoH (encrypting the hop to the
resolver) exist, but you cannot assume they are always present.

This is TLS's single most important design decision: **the client does not trust DNS; it verifies the server's
identity with the certificate**. Sent to the wrong IP, the handshake fails, as long as the client checks the
certificate and the host name (lesson 4, demo 5). Disabling certificate validation turns every DNS fault into a
successful MITM.

A Java note: the JVM caches DNS results (`networkaddress.cache.ttl`); in environments where IPs change quickly
(Kubernetes, cloud) this value decides whether clients follow new IPs.

## 5. HTTP and HTTPS

```bash
curl -v http://127.0.0.1:39100/          # python3 -m http.server 39100 --bind 127.0.0.1
```

```
> GET / HTTP/1.1
> Host: 127.0.0.1:39100
> User-Agent: curl/8.7.1
> Accept: */*
>
< HTTP/1.0 200 OK
< Server: SimpleHTTP/0.6 Python/3.13.3
< Content-type: text/html; charset=utf-8
```

HTTP is text: those lines travel on the wire exactly like that, including `Authorization`, `Cookie`, and bodies with
passwords.

| | HTTP | HTTPS (HTTP over TLS) |
|---|---|---|
| Content (URL path, headers, body) | Exposed, modifiable | Encrypted, modification detected |
| Who the server is | Unknown | Authenticated by certificate |
| Exposed | Everything | IPs, ports, domain (SNI), sizes, timing |
| Default port | 80 | 443 |

HTTP/2 in practice always runs over TLS; HTTP/3 runs over QUIC, where TLS 1.3 is built into the protocol.

### See it: what a device in the middle can read

[PlaintextVsTlsEavesdropDemo.java](../demos/PlaintextVsTlsEavesdropDemo.java) sets up a client, a server and a
**relay** between them. The relay stands for any device on the path: it forwards faithfully and keeps a copy.

```bash
cd network-security
./demos/create-demo-pki.sh            # once: creates a CA and server/client certificates for the TLS demos
javac -d out demos/*.java
java -cp out PlaintextVsTlsEavesdropDemo broken
```

```
mode=broken transport=plain TCP
client sent:      LOGIN user=alice password=S3cret-Pa55
server replied:   OK welcome alice
eavesdropper saw: 38 bytes: LOGIN user=alice password=S3cret-Pa55.
password visible on the wire: true
RESULT: WRONG (anyone on the path can read the password)
```

Neither client nor server can tell they are being read. The `fixed` (TLS) mode is in lesson 3.

## 6. What each layer provides: a map to the following lessons

| Question | Answered by | Lesson |
|----------|-------------|--------|
| Is data read/modified on the path? | TLS (AES-GCM, ECDHE) | 2, 3 |
| Is the server really `api.bank.com`? | X.509 certificate, CA, truststore | 4 |
| Is the client (a service) really `order-service`? | mTLS, or a client-credentials token | 3, 4, 5 |
| Who is the user, what may they do? | Sessions, OAuth 2.0, OIDC, JWT, RBAC/ABAC | 5 |
| Is the request forged, replayed, flooded? | HMAC signatures, timestamps, rate limits | 6 |
| Configuring all of the above in Java/Spring | `SSLContext`, SSL bundles, filter chains, resource server | 7 |

## 7. Commands used throughout

| Command | Used to |
|---------|---------|
| `dig <name>` | Query DNS |
| `nc -vz <host> <port>` | Check whether a TCP port is open |
| `lsof -nP -iTCP -sTCP:LISTEN` / `ss -tlnp` | Which ports and addresses the machine listens on (`0.0.0.0` = every interface) |
| `curl -v <url>` | See the request/response and the TLS handshake |
| `openssl s_client -connect host:443 -servername host` | See the certificate chain, TLS version and cipher suite |
| `keytool -list -v -keystore x.p12` | Inspect a keystore/truststore |
| `tcpdump` / Wireshark | Capture packets (needs root) |

## 8. Self-check

1. With HTTPS, what can the network operator still learn about your request?
2. Why does TLS not need to trust DNS?
3. Why is spoofing the source IP easier with UDP than with TCP?
4. A Java service calling an external API takes 300 ms on average although the API only needs 20 ms. What do you
   suspect first?

Short answers: (1) The destination IP, port, domain (SNI, DNS), sizes and timing; not the path, headers or body.
(2) The client checks a certificate signed by a trusted CA for the right name; whoever forges DNS lacks that
certificate's private key. (3) TCP requires the sender to receive the SYN-ACK (sent to the real IP) to complete the
handshake; UDP has no handshake. (4) A new TCP + TLS connection per call (several RTTs + asymmetric cryptography);
use connection pooling/keep-alive.

Next: [02 — Cryptography](02-cryptography.md).
