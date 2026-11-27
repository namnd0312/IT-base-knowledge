# Network Security

Bảo mật mạng cho lập trình viên Java backend: dữ liệu được bảo vệ thế nào khi đi qua mạng, các hệ thống xác thực
danh tính của nhau ra sao, và triển khai trong Java/Spring Boot.
Network security for Java backend developers: how data is protected in transit, how systems authenticate each other,
and how to implement it in Java/Spring Boot.

| Ngôn ngữ / Language | Bắt đầu ở / Start here |
|---------------------|------------------------|
| Tiếng Việt | [vn/README.md](vn/README.md) |
| English | [en/README.md](en/README.md) |

Hai phiên bản có cùng nội dung và dùng chung demo.
Both versions cover the same content and share the demos.

```
network-security/
├── vn/                8 bài + README (tiếng Việt)
├── en/                8 lessons + README (English)
├── demos/             9 demo Java thuần (JDK 21) + create-demo-pki.sh / 9 plain-Java demos + PKI script
└── spring-boot-demo/  HTTPS, mTLS, OAuth2 resource server (Spring Boot 4.1, 8 integration tests)
```

Mọi demo chỉ chạy trên `127.0.0.1`. Khoá và keystore demo được sinh vào `out/` (đã ignore), không commit.
Every demo runs on `127.0.0.1` only. Demo keys and keystores are generated into `out/` (ignored), never committed.
