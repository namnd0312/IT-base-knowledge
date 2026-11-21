# Java Memory — từ con số không đến trace & fix lỗi

Bộ tài liệu tự học về bộ nhớ trong Java: hiểu JVM dùng bộ nhớ thế nào, các lỗi hay gặp,
cách lần ra nguyên nhân và cách sửa. Có 5 demo chạy được, mỗi demo có bản lỗi và bản đã fix.

## Lộ trình đọc

| # | Tài liệu | Nội dung | Thời gian |
|---|----------|----------|-----------|
| 1 | [01-nen-tang-bo-nho-jvm.md](01-nen-tang-bo-nho-jvm.md) | Stack, heap, metaspace, GC, GC root, memory leak là gì | 30 phút |
| 2 | [02-cac-loi-memory-thuong-gap.md](02-cac-loi-memory-thuong-gap.md) | Bảng tra từng lỗi `OutOfMemoryError`, các kiểu leak kinh điển | 20 phút |
| 3 | [03-cong-cu-va-quy-trinh-trace.md](03-cong-cu-va-quy-trinh-trace.md) | Quy trình điều tra, `jcmd`/`jstat`/heap dump/MAT/NMT/GC log | 30 phút |
| 4 | [04-thuc-hanh-demo-tim-va-fix.md](04-thuc-hanh-demo-tim-va-fix.md) | Lab từng bước: chạy lỗi → trace → tìm nguyên nhân → fix → kiểm chứng | 60–90 phút |

Nếu đang gặp sự cố thật: đọc thẳng bảng tra ở tài liệu 2, rồi quy trình ở tài liệu 3.

## Demo

| Demo | Lỗi tái hiện | Vùng nhớ |
|------|--------------|----------|
| [HeapLeakUnboundedCacheDemo](demos/HeapLeakUnboundedCacheDemo.java) | `OutOfMemoryError: Java heap space` do cache static không giới hạn | Heap |
| [StackOverflowDeepRecursionDemo](demos/StackOverflowDeepRecursionDemo.java) | `StackOverflowError` do đệ quy sâu theo dữ liệu | Stack |
| [MetaspaceClassLoaderLeakDemo](demos/MetaspaceClassLoaderLeakDemo.java) | `OutOfMemoryError: Metaspace` do giữ ClassLoader | Metaspace |
| [ThreadLocalLeakInThreadPoolDemo](demos/ThreadLocalLeakInThreadPoolDemo.java) | `OutOfMemoryError: Java heap space` do `ThreadLocal` không `remove()` | Heap |
| [DirectBufferOffHeapLeakDemo](demos/DirectBufferOffHeapLeakDemo.java) | `OutOfMemoryError: ... direct buffer memory` | Native (ngoài heap) |

## Chạy nhanh

Yêu cầu: JDK 17 trở lên (tài liệu được kiểm chứng trên JDK 21.0.5, macOS). Không cần Maven.

```bash
cd Java/memory
javac -d out demos/*.java
mkdir -p dumps

# Bản lỗi: sẽ OOM sau khoảng 15 giây và sinh heap dump vào dumps/
java -Xmx64m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=dumps \
     -cp out HeapLeakUnboundedCacheDemo leak

# Bản đã fix: chạy hết và in DONE
java -Xmx64m -cp out HeapLeakUnboundedCacheDemo fixed
```

Mọi demo nhận tham số `leak` hoặc `fixed`, và `-DpauseMs=<n>` để chỉnh tốc độ
(tăng lên nếu cần thêm thời gian gắn công cụ vào process đang chạy).

`out/` và `dumps/` đã được ignore trong [.gitignore](.gitignore) — heap dump chứa toàn bộ dữ liệu
trong bộ nhớ, không bao giờ commit.
