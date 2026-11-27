# 01 — Nền tảng thread

Mục tiêu: sau bài này bạn biết thread là gì, tạo và dừng thread đúng cách, đọc được trạng thái của một thread,
và hiểu vì sao "chạy nhiều thread" không tự động nghĩa là "chạy nhanh hơn".

## 1. Process và thread

| | Process | Thread |
|---|---|---|
| Là gì | Một chương trình đang chạy | Một luồng thực thi bên trong process |
| Bộ nhớ | Riêng, cách ly với process khác | **Dùng chung heap** với các thread cùng process |
| Riêng của nó | Toàn bộ không gian địa chỉ | Stack, program counter |
| Chi phí tạo | Cao | Thấp hơn, nhưng không miễn phí (mỗi platform thread tốn khoảng 1 MB stack) |
| Giao tiếp | Qua socket, pipe, file | Đọc ghi thẳng vào object chung |

Một JVM là một process. Chương trình "một luồng" thật ra đã có nhiều thread: `main`, các thread của GC, JIT...

Điểm mấu chốt cho toàn bộ tài liệu: **thread dùng chung heap**. Đó là lý do chúng phối hợp được dễ dàng, và
cũng là nguồn gốc của mọi lỗi đa luồng (bài 2).

```
┌──────────────────────────── JVM process ─────────────────────────────┐
│                                                                      │
│   HEAP (dùng chung): object, field static                            │
│        ▲            ▲             ▲                                  │
│        │            │             │                                  │
│   ┌────┴────┐  ┌────┴────┐  ┌─────┴───┐                              │
│   │ Thread 1│  │ Thread 2│  │ Thread 3│   mỗi thread: stack riêng,   │
│   │  stack  │  │  stack  │  │  stack  │   biến cục bộ riêng          │
│   └─────────┘  └─────────┘  └─────────┘                              │
└──────────────────────────────────────────────────────────────────────┘
```

Biến cục bộ nằm trên stack nên luôn an toàn. Thứ cần lo là **object trên heap mà hơn một thread chạm tới**.

## 2. Concurrency và parallelism

| | Concurrency (đồng thời) | Parallelism (song song) |
|---|---|---|
| Nghĩa | Nhiều việc **cùng tiến triển**, xen kẽ nhau | Nhiều việc **chạy cùng một thời điểm** |
| Cần | Một core là đủ | Nhiều core |
| Ví dụ | Một đầu bếp nấu ba món, đảo qua lại | Ba đầu bếp, mỗi người một món |
| Lợi ích | Không ai phải chờ ai: phản hồi tốt | Xong sớm hơn: thông lượng cao |

Dùng thread vì hai lý do khác nhau, và cách tối ưu cũng khác nhau:

- **Việc chờ I/O** (gọi database, HTTP): thread phần lớn thời gian ngồi chờ. Nhiều thread giúp phục vụ nhiều
  request cùng lúc dù chỉ có vài core.
- **Việc tính toán** (nén ảnh, tính báo cáo): thread dùng CPU liên tục. Nhiều thread hơn số core không giúp gì,
  chỉ thêm chi phí chuyển ngữ cảnh.

Giới hạn cần biết (định luật Amdahl): nếu 20% công việc buộc phải chạy tuần tự thì dù thêm bao nhiêu core,
tốc độ cũng không vượt quá 5 lần. Phần tuần tự, thường là đoạn phải giữ khoá, quyết định trần hiệu năng.

## 3. Tạo thread

```java
// Cách 1: kế thừa Thread (ít dùng: trộn "việc cần làm" với "cách chạy")
static class DownloadThread extends Thread {
    @Override public void run() { System.out.println("running in " + Thread.currentThread().getName()); }
}

Thread t1 = new DownloadThread();

// Cách 2: truyền Runnable (nên dùng), luôn đặt tên thread
Thread t2 = new Thread(() -> System.out.println("hello from " + Thread.currentThread().getName()), "worker-1");

// Cách 3: builder (JDK 21)
Thread t3 = Thread.ofPlatform().name("worker-2").daemon(true).unstarted(() -> {});

t1.start(); t2.start(); t3.start();
```

```
running in Thread-0
hello from worker-1
```

Ba điều phải nhớ:

- **`start()` chứ không phải `run()`.** `start()` tạo thread mới rồi thread đó gọi `run()`. Gọi thẳng `run()` chỉ là
  gọi một method bình thường trên thread hiện tại, không có gì chạy song song.
- **Đặt tên thread.** `Thread-0` trong thread dump không nói lên điều gì; `payment-worker-3` thì có.
- **Trong dự án thật hầu như không tự `new Thread`.** Dùng thread pool (bài 5). Tự tạo thread cho mỗi việc là
  cách nhanh nhất để hết bộ nhớ khi tải tăng.

Mỗi `Thread` chỉ `start()` được một lần. Gọi lần hai ném `IllegalThreadStateException`.

## 4. Vòng đời và trạng thái

```
                 start()
   NEW ───────────────────▶ RUNNABLE ──────────────────▶ TERMINATED
                              │  ▲                  run() kết thúc
        chờ lấy khoá          │  │  lấy được khoá
        synchronized          ▼  │
                            BLOCKED

        wait(), join(),       │  ▲  notify(), thread kia xong,
        park()                ▼  │  unpark()
                            WAITING

        sleep(n), wait(n),    │  ▲  hết giờ hoặc được đánh thức
        join(n)               ▼  │
                         TIMED_WAITING
```

| Trạng thái | Nghĩa | Thấy trong thread dump khi |
|-----------|-------|----------------------------|
| `NEW` | Đã tạo, chưa `start()` | Hiếm |
| `RUNNABLE` | Đang chạy hoặc sẵn sàng chạy. Bao gồm cả lúc đang chờ I/O ở tầng hệ điều hành | Đang tính toán, đọc socket |
| `BLOCKED` | Chờ vào một khối `synchronized` mà thread khác đang giữ | Tranh chấp khoá, deadlock |
| `WAITING` | Chờ vô thời hạn một tín hiệu từ thread khác | Thread pool rảnh, `future.get()` |
| `TIMED_WAITING` | Như trên nhưng có thời hạn | `sleep`, chờ có timeout |
| `TERMINATED` | `run()` đã kết thúc | Không còn trong dump |

Output thật khi đọc `getState()` ở từng thời điểm:

```
before start : NEW
sleeping     : TIMED_WAITING
in wait()    : WAITING
wants a lock : BLOCKED
after finish : TERMINATED
```

Phân biệt `BLOCKED` và `WAITING` là kỹ năng đọc thread dump quan trọng nhất (bài 9): `BLOCKED` là "muốn một cái khoá
mà chưa được", `WAITING` là "tự nguyện ngồi chờ ai đó gọi".

## 5. `join`, `sleep`, `yield`

| Method | Tác dụng | Ghi chú |
|--------|----------|---------|
| `t.join()` | Thread hiện tại chờ `t` kết thúc | Có bản có timeout: `t.join(1000)` |
| `Thread.sleep(ms)` | Thread hiện tại ngủ | **Không nhả khoá** đang giữ |
| `Thread.yield()` | Gợi ý nhường CPU | Chỉ là gợi ý; gần như không bao giờ cần |

```java
Thread worker = new Thread(task);
worker.start();
worker.join();          // không có dòng này, main có thể đọc kết quả khi worker chưa xong
```

Không dùng `sleep` để "chờ thread kia xong". Máy chậm hơn một chút là sai. Dùng `join`, `Future`, hoặc
`CountDownLatch` (bài 7).

## 6. Dừng thread: interrupt

Java không có cách an toàn để ép một thread dừng. `Thread.stop()` đã bị loại bỏ vì nó giết thread giữa chừng,
để lại dữ liệu dở dang. Cách duy nhất là **hợp tác**: gửi yêu cầu dừng, thread tự kiểm tra và tự kết thúc.

`thread.interrupt()` làm hai việc tuỳ thread đang ở đâu:

- Đang bị chặn trong `sleep`, `wait`, `join`, `BlockingQueue.take`...: method đó ném `InterruptedException`
  **và xoá cờ interrupt**.
- Đang chạy bình thường: chỉ bật cờ. Thread phải tự kiểm tra `isInterrupted()`.

Mẫu chuẩn:

```java
Thread worker = new Thread(() -> {
    while (!Thread.currentThread().isInterrupted()) {     // kiểm tra cờ
        try {
            Thread.sleep(200);                            // việc có thể bị chặn
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();           // bật lại cờ đã bị xoá
            break;
        }
    }
    System.out.println("worker: cleaned up and stopped");
});
worker.start();
Thread.sleep(300);
worker.interrupt();
worker.join();
```

Lỗi phổ biến nhất về interrupt là **nuốt exception**:

```java
try {
    Thread.sleep(1000);
} catch (InterruptedException e) {
    // bỏ trống: yêu cầu dừng biến mất, thread tiếp tục chạy như không có gì
}
```

Quy tắc: bắt `InterruptedException` thì hoặc ném tiếp, hoặc gọi `Thread.currentThread().interrupt()` rồi thoát.
Thread pool dừng thread bằng chính cơ chế này (`shutdownNow`), nên code nuốt interrupt là code không tắt được.

## 7. Daemon thread

| | User thread (mặc định) | Daemon thread |
|---|---|---|
| JVM có chờ nó không | Có: JVM chỉ thoát khi mọi user thread kết thúc | Không: JVM thoát là nó bị bỏ ngang |
| Dùng cho | Công việc phải hoàn thành | Việc nền có thể bỏ dở: dọn cache, heartbeat |

```java
thread.setDaemon(true);   // phải gọi trước start()
```

Triệu chứng "chương trình chạy xong `main` mà không thoát": còn một user thread nào đó, thường là thread pool chưa
`shutdown()`. Ngược lại, đừng dùng daemon thread cho việc ghi file hay ghi database: nó có thể bị cắt giữa chừng.

## 8. Exception trong thread

Exception ném ra trong `run()` không bay về thread đã tạo ra nó. Thread đó chết, in stack trace ra `stderr`, và
phần còn lại của chương trình chạy tiếp như không có gì.

```java
thread.setUncaughtExceptionHandler((t, error) ->
        log.error("Thread {} chết vì lỗi", t.getName(), error));
```

Với thread pool, tình hình còn kín hơn: exception của task chạy bằng `submit()` nằm im trong `Future` cho tới khi
ai đó gọi `get()` (bài 5).

## 9. Tự kiểm tra

1. Gọi `thread.run()` thay vì `thread.start()` thì chuyện gì xảy ra?
2. Thread ở trạng thái `BLOCKED` khác `WAITING` thế nào?
3. Vì sao phải gọi `Thread.currentThread().interrupt()` trong khối `catch (InterruptedException e)`?
4. `main` đã chạy hết nhưng JVM không thoát. Nguyên nhân hay gặp nhất?

Đáp án ngắn: (1) `run()` chạy ngay trên thread hiện tại, không có thread mới. (2) `BLOCKED` đang chờ lấy một khoá
`synchronized`; `WAITING` đang chờ một thread khác đánh thức. (3) Vì khi ném exception, cờ interrupt đã bị xoá;
không bật lại thì code phía ngoài không biết có yêu cầu dừng. (4) Còn user thread đang sống, thường là thread pool
chưa `shutdown()`.

Bài tiếp theo: [02 — Race condition, visibility và Java Memory Model](02-race-condition-visibility-va-java-memory-model.md).
