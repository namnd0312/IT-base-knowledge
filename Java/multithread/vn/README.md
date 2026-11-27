# Java Multithreading — từ con số không đến viết và debug được code đa luồng

Bộ tài liệu tự học lập trình đa luồng trong Java: thread là gì, vì sao code đa luồng sai theo những cách
code một luồng không bao giờ sai, các công cụ để viết đúng (lock, atomic, thread pool, `CompletableFuture`,
virtual thread), và cách lần ra nguyên nhân khi ứng dụng treo hoặc ra kết quả sai.
Có 8 [demo chạy được](../demos/), mỗi demo có bản lỗi và bản đã fix; output trong tài liệu lấy từ các demo này.

Phiên bản đã kiểm chứng: **JDK 21.0.5** (macOS, 8 core). Con số cụ thể (số lần mất cập nhật, thời gian chạy)
sẽ khác trên máy bạn; kết luận thì không đổi.

English: [../en/README.md](../en/README.md)

## Lộ trình đọc

| # | Tài liệu | Nội dung | Thời gian |
|---|----------|----------|-----------|
| 1 | [01-nen-tang-thread.md](01-nen-tang-thread.md) | Process và thread, tạo thread, vòng đời, `join`, `interrupt`, daemon | 30 phút |
| 2 | [02-race-condition-visibility-va-java-memory-model.md](02-race-condition-visibility-va-java-memory-model.md) | Ba loại lỗi gốc: atomicity, visibility, ordering; `volatile`; happens-before | 40 phút |
| 3 | [03-synchronized-lock-va-deadlock.md](03-synchronized-lock-va-deadlock.md) | `synchronized`, `ReentrantLock`, `ReadWriteLock`, deadlock và cách tránh | 45 phút |
| 4 | [04-atomic-concurrent-collection-va-chien-luoc-thread-safe.md](04-atomic-concurrent-collection-va-chien-luoc-thread-safe.md) | Atomic, CAS, `ConcurrentHashMap`, `BlockingQueue`, immutability, `ThreadLocal` | 40 phút |
| 5 | [05-thread-pool-va-executor.md](05-thread-pool-va-executor.md) | `ExecutorService`, tham số `ThreadPoolExecutor`, chọn kích thước pool, tắt pool | 45 phút |
| 6 | [06-completablefuture.md](06-completablefuture.md) | Ghép các tác vụ bất đồng bộ, xử lý lỗi, timeout | 40 phút |
| 7 | [07-synchronizer-va-phoi-hop-thread.md](07-synchronizer-va-phoi-hop-thread.md) | `wait`/`notify`, `CountDownLatch`, `Semaphore`, `CyclicBarrier`, producer–consumer | 35 phút |
| 8 | [08-virtual-thread.md](08-virtual-thread.md) | Virtual thread (JDK 21): khi nào dùng, khi nào không, pinning | 30 phút |
| 9 | [09-debug-thread-dump-va-bang-tra-loi.md](09-debug-thread-dump-va-bang-tra-loi.md) | Đọc thread dump, `jstack`/`jcmd`, bảng tra lỗi, checklist review | 35 phút |
| 10 | [10-thuc-hanh-voi-demo.md](10-thuc-hanh-voi-demo.md) | Lab 8 demo: chạy lỗi → quan sát → tìm nguyên nhân → fix → kiểm chứng | 60–90 phút |

Gợi ý: đọc 1 → 2 rồi chạy ngay demo 1 và 2 ở bài 10. Thấy tận mắt 8 triệu phép cộng chỉ còn chưa tới 2 triệu
thì các bài sau dễ vào hơn nhiều.

Nếu đang gặp sự cố thật (ứng dụng treo, CPU 100%, kết quả lúc đúng lúc sai): đọc thẳng
[bảng tra ở bài 9](09-debug-thread-dump-va-bang-tra-loi.md#7-bảng-tra-lỗi-thường-gặp).

## Bản đồ kiến thức

```
                    Thread là gì, sống chết thế nào (01)
                                  │
                                  ▼
              Vì sao chia sẻ dữ liệu giữa các thread lại sai (02)
                                  │
         ┌────────────────────────┼─────────────────────────┐
         ▼                        ▼                         ▼
   Khoá lại (03)          Không cần khoá (04)       Phối hợp, chờ nhau (07)
   synchronized, Lock     atomic, concurrent        latch, semaphore,
   deadlock               collection, immutable     wait/notify
         └────────────────────────┼─────────────────────────┘
                                  ▼
        Không tự tạo thread: thread pool (05), CompletableFuture (06),
                        virtual thread (08)
                                  ▼
                 Debug, thread dump, checklist (09)
```

## Demo

| Demo | Lỗi tái hiện | Bài |
|------|--------------|-----|
| [RaceConditionCounterDemo](../demos/RaceConditionCounterDemo.java) | Mất cập nhật do `counter++` không nguyên tử | 2 |
| [VisibilityStopFlagDemo](../demos/VisibilityStopFlagDemo.java) | Thread không bao giờ thấy cờ dừng vì thiếu `volatile` | 2 |
| [DeadlockTransferDemo](../demos/DeadlockTransferDemo.java) | Deadlock do hai thread lấy khoá ngược thứ tự | 3 |
| [CheckThenActConcurrentMapDemo](../demos/CheckThenActConcurrentMapDemo.java) | Dùng `ConcurrentHashMap` kiểu "đọc rồi ghi" vẫn mất dữ liệu | 4 |
| [ThreadPoolStarvationDeadlockDemo](../demos/ThreadPoolStarvationDeadlockDemo.java) | Pool tự kẹt: task chờ subtask nằm trong chính pool đó | 5 |
| [CompletableFutureCompositionDemo](../demos/CompletableFutureCompositionDemo.java) | Gọi tuần tự 1200 ms so với song song 500 ms; fallback khi lỗi | 6 |
| [SemaphoreConcurrencyLimitDemo](../demos/SemaphoreConcurrencyLimitDemo.java) | 20 lời gọi dội cùng lúc vào dịch vụ chỉ chịu được 3 | 7 |
| [VirtualThreadsBlockingIoDemo](../demos/VirtualThreadsBlockingIoDemo.java) | 2000 tác vụ chờ I/O: pool 100 thread mất 4 giây, virtual thread 0,2 giây | 8 |

Comment và output của demo viết bằng tiếng Anh vì dùng chung cho cả hai bản tài liệu.

## Chạy nhanh

Yêu cầu: JDK 21 trở lên (demo 8 dùng virtual thread). Không cần Maven.

```bash
cd Java/multithread
javac -d out demos/*.java

java -cp out RaceConditionCounterDemo broken   # sai: mất hàng triệu lần cộng
java -cp out RaceConditionCounterDemo fixed    # đúng: 8,000,000
```

```
mode=broken threads=8 incrementsPerThread=1,000,000
expected=8,000,000 actual=1,963,442 lost=6,036,558
RESULT: WRONG (lost updates)
```

Mọi demo nhận tham số chế độ (`broken`/`fixed`, riêng demo 6 và 8 có tên chế độ khác, xem bài 10) và in dòng
`RESULT:` ở cuối. `out/` đã được ignore trong [.gitignore](../.gitignore).
