# 08 — Virtual thread

Mục tiêu: sau bài này bạn hiểu virtual thread (chính thức từ JDK 21) khác platform thread ở đâu, biết khi nào nó giúp
và khi nào không, và tránh được các lỗi khi chuyển code cũ sang.

## 1. Vấn đề nó giải quyết

Mô hình truyền thống của server Java: **mỗi request một thread**. Code dễ viết, dễ đọc, dễ debug. Nhưng:

- Mỗi platform thread là một thread của hệ điều hành, tốn khoảng 1 MB stack và chi phí lập lịch của kernel.
- Một máy chỉ chịu được vài nghìn thread. Trong khi đó thread phục vụ request dành phần lớn thời gian **ngồi chờ**
  database hoặc dịch vụ khác.

Kết quả: máy còn thừa CPU nhưng hết thread. Cách chữa trước đây là code bất đồng bộ (`CompletableFuture`, reactive):
hiệu quả, nhưng khó đọc, stack trace vô nghĩa, khó debug.

Virtual thread giữ nguyên kiểu code "mỗi request một thread, viết tuần tự, cứ chặn", và bỏ đi giới hạn số thread.

## 2. Nó hoạt động thế nào

```
   Virtual thread (hàng trăm nghìn, do JVM quản lý, rất nhẹ)
   ┌──┐┌──┐┌──┐┌──┐┌──┐┌──┐┌──┐┌──┐┌──┐┌──┐ ...
   └┬─┘└──┘└┬─┘└──┘└──┘└┬─┘└──┘└──┘└──┘└──┘
    │ mount │           │         các cái còn lại đang chờ I/O: nằm trên heap,
    ▼       ▼           ▼         không chiếm thread nào
   ┌────┐ ┌────┐     ┌────┐
   │ C1 │ │ C2 │ ... │ Cn │   Carrier thread = platform thread, số lượng ≈ số core
   └────┘ └────┘     └────┘
```

- Virtual thread chạy **trên** một carrier thread (một platform thread thuộc `ForkJoinPool` riêng của JVM).
- Khi virtual thread gặp thao tác chặn (`sleep`, đọc socket, `lock`, `BlockingQueue.take`...), JVM **gỡ** nó khỏi
  carrier, cất stack của nó lên heap, và carrier quay sang chạy virtual thread khác.
- Khi thao tác xong, virtual thread được gắn lại lên một carrier bất kỳ và chạy tiếp.

Với code của bạn, nó vẫn là một `Thread` bình thường: vẫn `Thread.currentThread()`, vẫn stack trace đầy đủ,
vẫn debug từng bước được.

| | Platform thread | Virtual thread |
|---|---|---|
| Ánh xạ | 1 : 1 với thread của hệ điều hành | Rất nhiều : ít carrier |
| Bộ nhớ | Khoảng 1 MB stack, cấp trước | Vài KB, lớn dần theo nhu cầu, nằm trên heap |
| Số lượng hợp lý | Hàng nghìn | Hàng trăm nghìn tới hàng triệu |
| Tạo | Đắt: dùng pool | Rất rẻ: tạo mới cho mỗi việc, **không pool** |
| Khi chặn | Chiếm thread của hệ điều hành | Nhả carrier |
| Daemon | Tuỳ chọn | Luôn là daemon |

## 3. Demo

[VirtualThreadsBlockingIoDemo.java](../demos/VirtualThreadsBlockingIoDemo.java): 2000 tác vụ, mỗi cái chặn 200 ms
(mô phỏng lời gọi database).

```java
try (ExecutorService executor = virtual
        ? Executors.newVirtualThreadPerTaskExecutor()
        : Executors.newFixedThreadPool(PLATFORM_POOL_SIZE)) {
    for (int i = 0; i < TASKS; i++) {
        executor.execute(() -> {
            Thread.sleep(BLOCK_MILLIS);     // lời gọi chặn
            completed.incrementAndGet();
        });
    }
}
```

```
mode=platform(pool=100) tasks=2,000 blockMillis=200
completed=2,000 elapsed=4075 ms

mode=virtual tasks=2,000 blockMillis=200
completed=2,000 elapsed=230 ms
```

Pool 100 thread chỉ chạy được 100 việc một lượt: 20 lượt × 200 ms = 4 giây. Với virtual thread, cả 2000 việc cùng
"ngủ" một lúc trên vài carrier, và xong sau hơn 200 ms.

Lưu ý cách đọc kết quả: không việc nào chạy nhanh hơn. Mỗi việc vẫn mất 200 ms. Thứ tăng lên là **số việc chờ được
cùng lúc**.

## 4. Cách dùng

```java
Thread v1 = Thread.ofVirtual().name("vt-1").start(() -> System.out.println(Thread.currentThread()));
Thread v2 = Thread.startVirtualThread(() -> { });

try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    Future<String> f = executor.submit(() -> "done on " + Thread.currentThread());
    System.out.println(f.get());
}
```

```
VirtualThread[#47,vt-1]/runnable@ForkJoinPool-1-worker-1
true daemon=true
done on VirtualThread[#52]/runnable@ForkJoinPool-1-worker-3
```

Phần sau dấu `@` là carrier đang chở nó: `ForkJoinPool-1-worker-1`.

Trong Spring Boot 3.2 trở lên, một dòng cấu hình là đủ để mỗi request chạy trên một virtual thread:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

## 5. Khi nào giúp, khi nào không

| Loại việc | Virtual thread | Lý do |
|-----------|----------------|-------|
| Chờ I/O: gọi database, HTTP, hàng đợi | **Rất hợp** | Chặn là nhả carrier |
| Rất nhiều kết nối đồng thời, mỗi cái phần lớn là chờ | **Rất hợp** | Không còn giới hạn bởi số thread |
| Tính toán nặng (CPU-bound) | Không giúp | Vẫn chỉ có bấy nhiêu core; việc tính toán không nhả carrier |
| Việc cần thông lượng CPU tối đa | Dùng platform pool cỡ số core | Như bài 5 |

Virtual thread không làm code nhanh hơn. Nó làm việc **chờ** trở nên rẻ.

## 6. Những thứ phải đổi cách nghĩ

### Không pool virtual thread

Pool tồn tại vì thread đắt. Virtual thread rẻ nên tạo mới cho mỗi việc rồi bỏ. Đưa virtual thread vào một pool cỡ cố
định là tự gắn lại đúng cái giới hạn vừa bỏ đi.

### Giới hạn đồng thời bằng `Semaphore`

Trước đây kích thước pool vô tình đóng vai giới hạn: pool 50 thread thì tối đa 50 truy vấn cùng lúc. Với virtual
thread, 10.000 request sẽ thành 10.000 truy vấn đồng thời dội vào một database chỉ có 20 connection.

Giới hạn phải được nói ra rõ ràng (bài 7):

```java
Semaphore dbPermits = new Semaphore(20);

dbPermits.acquire();
try {
    // gọi database
} finally {
    dbPermits.release();
}
```

Connection pool của database, nếu có, vẫn làm vai trò này. Điều cần xem lại là những tài nguyên trước giờ **chỉ**
được bảo vệ bởi kích thước thread pool.

### Pinning

Có trường hợp virtual thread chặn mà **không** nhả được carrier; nó bị "ghim" (pinned). Carrier đó bị chiếm suốt thời
gian chặn, và vì số carrier chỉ xấp xỉ số core, vài virtual thread bị ghim là đủ làm nghẽn toàn bộ.

| Nguyên nhân | JDK 21 đến 23 | JDK 24 trở lên |
|-------------|---------------|----------------|
| Chặn bên trong khối `synchronized` | **Bị ghim** | Không còn (JEP 491) |
| Chặn bên trong native method (JNI) | Bị ghim | Vẫn bị ghim |

Trên JDK 21, với đoạn code vừa giữ khoá vừa làm I/O, thay `synchronized` bằng `ReentrantLock`:

```java
private final ReentrantLock lock = new ReentrantLock();

lock.lock();
try {
    callRemote();        // chặn trong khi giữ khoá: với ReentrantLock, carrier vẫn được nhả
} finally {
    lock.unlock();
}
```

`synchronized` chỉ bao phép tính ngắn trong bộ nhớ thì không cần đổi. Tìm chỗ bị ghim trên JDK 21 bằng
`-Djdk.tracePinnedThreads=full`, hoặc sự kiện `jdk.VirtualThreadPinned` trong JFR. Thư viện cũ (driver JDBC, HTTP
client) có thể tự gây pinning bên trong; dùng bản mới.

### `ThreadLocal`

Vẫn hoạt động, mỗi virtual thread có bản riêng. Nhưng hai thói quen cũ trở thành vấn đề:

- **Dùng `ThreadLocal` làm cache object đắt tiền** (buffer, formatter) với giả định thread được dùng lại: virtual
  thread không được dùng lại, nên cache không bao giờ trúng, và một triệu thread nghĩa là một triệu bản sao.
- Truyền ngữ cảnh qua `ThreadLocal` thì vẫn ổn về mặt đúng đắn. `ScopedValue` (preview ở JDK 21, chính thức ở JDK 25)
  là cách thay thế được thiết kế cho virtual thread.

## 7. Structured concurrency

`StructuredTaskScope` coi một nhóm việc con chạy song song như một khối: cả nhóm cùng kết thúc, một việc lỗi thì các
việc còn lại bị huỷ, và thread dump thể hiện được quan hệ cha con. Nó giải đúng bài toán của demo 6 với code kiểu chặn
thay cho chuỗi callback.

API này vẫn ở trạng thái **preview** từ JDK 21 đến JDK 25 và đã đổi hình dạng giữa các phiên bản. Vì vậy tài liệu này
không đưa code mẫu; khi dùng, đọc JEP ứng với đúng phiên bản JDK của bạn và bật `--enable-preview`.

## 8. Quan sát và debug

- `jstack` và `jcmd <pid> Thread.print` chỉ liệt kê platform thread (carrier hiện ra dưới tên `ForkJoinPool-1-worker-N`).
- Dump có virtual thread: `jcmd <pid> Thread.dump_to_file -format=json <file>`.
- Vì có thể có hàng trăm nghìn virtual thread, đặt tên có ý nghĩa và dựa vào log, metrics hơn là đọc dump bằng mắt.

## 9. Checklist chuyển sang virtual thread

- [ ] Khối lượng công việc chủ yếu là chờ I/O, không phải tính toán.
- [ ] Không pool virtual thread; dùng `newVirtualThreadPerTaskExecutor()`.
- [ ] Mọi tài nguyên có sức chứa hữu hạn đều có giới hạn rõ ràng (`Semaphore`, connection pool).
- [ ] Trên JDK 21–23: không có `synchronized` bao quanh I/O ở đường nóng; đã chạy thử với `jdk.tracePinnedThreads`.
- [ ] Không dùng `ThreadLocal` làm cache theo thread.
- [ ] Driver và thư viện đã ở phiên bản hỗ trợ virtual thread.
- [ ] Đã đo dưới tải thật trước và sau khi bật.

## 10. Tự kiểm tra

1. Một dịch vụ nén ảnh (CPU-bound) chuyển sang virtual thread có nhanh hơn không?
2. Vì sao không nên tạo pool chứa virtual thread?
3. Trước khi chuyển, pool 50 thread gọi một API giới hạn 50 kết nối. Sau khi chuyển cần thêm gì?
4. Trên JDK 21, method `synchronized` có lời gọi JDBC bên trong gây ra vấn đề gì với virtual thread?

Đáp án ngắn: (1) Không; số core không đổi, việc tính toán không nhả carrier. (2) Pool tồn tại để dùng lại thread đắt;
virtual thread rẻ, pool chỉ đặt lại giới hạn cũ. (3) Một `Semaphore(50)` quanh lời gọi, vì giới hạn ngầm của kích
thước pool đã mất. (4) Pinning: virtual thread giữ carrier suốt lời gọi JDBC; vài cái là hết carrier.

Bài tiếp theo: [09 — Debug, thread dump và bảng tra lỗi](09-debug-thread-dump-va-bang-tra-loi.md).
