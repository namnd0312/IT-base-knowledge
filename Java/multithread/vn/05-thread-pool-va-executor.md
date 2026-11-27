# 05 — Thread pool và Executor

Mục tiêu: sau bài này bạn hiểu từng tham số của `ThreadPoolExecutor`, chọn được kích thước pool có căn cứ, tắt pool
đúng cách, và tránh được hai sự cố kinh điển: hàng đợi phình vô hạn và pool tự kẹt.

## 1. Vì sao không tự `new Thread`

| Tự tạo thread cho mỗi việc | Thread pool |
|----------------------------|-------------|
| Tốn chi phí tạo và huỷ cho mỗi việc | Thread được dùng lại |
| Tải tăng thì số thread tăng không giới hạn, hết bộ nhớ | Số thread có trần |
| Không có chỗ để xếp hàng, từ chối, theo dõi | Có hàng đợi, chính sách từ chối, số liệu |
| Khó tắt cho gọn | `shutdown()` |

Thread pool tách **việc cần làm** (task) khỏi **cách chạy nó** (thread nào, khi nào). Bạn nộp task; pool quyết định.

## 2. `ExecutorService`

```java
ExecutorService pool = Executors.newFixedThreadPool(8);

pool.execute(() -> sendEmail());                    // Runnable, không cần kết quả
Future<Integer> future = pool.submit(() -> 21 * 2); // Callable, có kết quả

try {
    Integer result = future.get(2, TimeUnit.SECONDS);   // chặn tối đa 2 giây
} catch (TimeoutException e) {
    future.cancel(true);                                // gửi interrupt cho task
} catch (ExecutionException e) {
    System.out.println("task failed: " + e.getCause()); // exception thật nằm trong getCause()
}
```

| | `execute(Runnable)` | `submit(Callable/Runnable)` |
|---|---|---|
| Kết quả | Không | `Future` |
| Exception trong task | Tới `UncaughtExceptionHandler`, in ra `stderr` | **Nằm im trong `Future`** cho tới khi gọi `get()` |

Bẫy: `submit()` rồi không bao giờ gọi `get()` nghĩa là mọi exception của task biến mất không dấu vết. Hoặc dùng
`execute`, hoặc luôn xử lý `Future`, hoặc bọc `try/catch` có log trong task.

`future.get()` không có timeout có thể chặn mãi. Trong code phục vụ request, luôn dùng bản có timeout.

Chạy nhiều task và chờ tất cả:

```java
List<Callable<String>> calls = List.of(() -> "a", () -> "b");
for (Future<String> f : pool.invokeAll(calls)) {    // trả về khi mọi task xong
    System.out.println(f.get());
}
```

## 3. Bên trong `ThreadPoolExecutor`

Các method `Executors.newXxx` chỉ là cấu hình sẵn của một class: `ThreadPoolExecutor`. Hiểu bảy tham số của nó là
hiểu mọi loại pool.

```java
AtomicInteger seq = new AtomicInteger();
ThreadPoolExecutor pool = new ThreadPoolExecutor(
        4,                                       // corePoolSize
        8,                                       // maximumPoolSize
        60, TimeUnit.SECONDS,                    // keepAliveTime cho thread vượt core
        new ArrayBlockingQueue<>(100),           // hàng đợi CÓ giới hạn
        r -> new Thread(r, "report-worker-" + seq.incrementAndGet()),   // đặt tên thread
        new ThreadPoolExecutor.CallerRunsPolicy());                     // khi quá tải
```

Thứ tự xử lý khi một task được nộp, và đây là điểm hầu hết mọi người hiểu sai:

```
task mới
   │
   ▼
số thread < corePoolSize ? ──có──▶ tạo thread mới chạy task
   │ không
   ▼
hàng đợi còn chỗ ? ──────────có──▶ xếp vào hàng đợi
   │ không (đầy)
   ▼
số thread < maximumPoolSize ? ─có─▶ tạo thread mới chạy task
   │ không
   ▼
từ chối theo RejectedExecutionHandler
```

**Pool chỉ tạo thread vượt core khi hàng đợi đã đầy.** Hệ quả: với hàng đợi không giới hạn, `maximumPoolSize` không
bao giờ có tác dụng; pool mãi mãi chỉ có `corePoolSize` thread.

### Các pool dựng sẵn và rủi ro của chúng

| Factory | Cấu hình thật | Rủi ro |
|---------|---------------|--------|
| `newFixedThreadPool(n)` | core = max = n, hàng đợi **không giới hạn** | Task dồn lại vô hạn khi xử lý không kịp, hết bộ nhớ |
| `newCachedThreadPool()` | core = 0, max = vô hạn, không có hàng đợi | Số thread tăng vô hạn khi tải tăng |
| `newSingleThreadExecutor()` | Một thread, hàng đợi không giới hạn | Như fixed; bảo đảm chạy tuần tự |
| `newScheduledThreadPool(n)` | Chạy trễ hoặc định kỳ | Hàng đợi không giới hạn |
| `newVirtualThreadPerTaskExecutor()` | Một virtual thread cho mỗi task | Không có giới hạn đồng thời (bài 8) |

Với dịch vụ chạy production, tự khai báo `ThreadPoolExecutor` với hàng đợi có giới hạn và chính sách từ chối rõ ràng.

### Chính sách khi quá tải

| Handler | Hành vi | Dùng khi |
|---------|---------|----------|
| `AbortPolicy` (mặc định) | Ném `RejectedExecutionException` | Muốn người gọi biết và xử lý (trả 503) |
| `CallerRunsPolicy` | Thread nộp task tự chạy task đó | Muốn backpressure tự nhiên: người nộp bị chậm lại |
| `DiscardPolicy` | Bỏ task, im lặng | Việc bỏ được (metrics). Dễ che lỗi |
| `DiscardOldestPolicy` | Bỏ task chờ lâu nhất | Chỉ dữ liệu mới nhất có giá trị |

Từ chối là tính năng: hệ thống quá tải mà vẫn nhận mọi thứ thì sẽ sập toàn bộ; từ chối bớt thì phần còn lại sống.

## 4. Chọn kích thước pool

| Loại việc | Công thức | Ví dụ, máy 8 core |
|-----------|-----------|-------------------|
| Tính toán (CPU-bound) | số core, hoặc số core + 1 | 8 |
| Chờ I/O (I/O-bound) | số core × (1 + thời gian chờ / thời gian tính) | Chờ 90 ms, tính 10 ms: 8 × 10 = 80 |

```java
int cores = Runtime.getRuntime().availableProcessors();
```

Lưu ý:

- Trong container, `availableProcessors()` trả về giới hạn CPU của container, có thể là 1 hoặc 2.
- Công thức chỉ là điểm xuất phát. Giới hạn thật thường nằm ở phía sau: pool 200 thread gọi vào database chỉ có
  20 connection thì 180 thread chỉ để xếp hàng. Kích thước pool nên khớp với tài nguyên chậm nhất nó dùng.
- Đo rồi chỉnh. Nhiều thread hơn mức cần làm hệ thống chậm đi vì chuyển ngữ cảnh và tranh chấp.

**Tách pool theo loại việc.** Việc chậm (gọi dịch vụ ngoài) dùng chung pool với việc nhanh sẽ chiếm hết thread và
làm việc nhanh cũng chết theo. Mỗi phụ thuộc bên ngoài một pool riêng, có giới hạn riêng (mẫu bulkhead).

## 5. Pool tự kẹt (starvation deadlock)

Demo: [ThreadPoolStarvationDeadlockDemo.java](../demos/ThreadPoolStarvationDeadlockDemo.java). Pool 2 thread chạy 2 đơn hàng;
mỗi đơn nộp một subtask thanh toán vào **chính pool đó** rồi chờ kết quả.

```java
static String processOrder(int orderId, ExecutorService paymentPool) throws Exception {
    Future<String> payment = paymentPool.submit(() -> {
        Thread.sleep(200);
        return "paid";
    });
    return "order " + orderId + " -> " + payment.get();    // chặn một thread của pool
}
```

```
PID=52329 mode=broken
orderPool state: java.util.concurrent.ThreadPoolExecutor@330bedb4[Running, pool size = 2, active threads = 2, queued tasks = 2, completed tasks = 0]
RESULT: WRONG (no order finished in time: every pool thread waits for a queued subtask)
```

Đọc dòng trạng thái: 2 thread đều đang bận (`active threads = 2`), 2 subtask nằm trong hàng đợi (`queued tasks = 2`),
chưa việc nào xong. Hai thread bận chính là hai thread đang chờ hai subtask kia. Không còn ai để chạy subtask.

```
Thread 1: chạy order 1 ── chờ payment 1 ──┐
Thread 2: chạy order 2 ── chờ payment 2 ──┤   cả hai nằm trong hàng đợi,
Hàng đợi: [payment 1, payment 2] ◀────────┘   không còn thread nào rảnh
```

Không có khoá nào tham gia, nên `jstack` **không báo deadlock**. Thread dump chỉ cho thấy mọi thread của pool
`WAITING` trong `FutureTask.get` (bài 9).

Trong thực tế lỗi này chỉ lộ ra khi tải đủ cao để mọi thread cùng bận, tức là đúng lúc tệ nhất.

Cách sửa:

| Cách | Ghi chú |
|------|---------|
| Subtask chạy ở pool khác | Bản fix của demo |
| Không chặn chờ: ghép bằng `CompletableFuture` | Thread nộp việc xong là được trả về pool (bài 6) |
| Chạy subtask ngay trên thread hiện tại | Khi không thật sự cần song song |

```
mode=fixed
order 1 -> paid
order 2 -> paid
RESULT: CORRECT (all orders completed)
```

Quy tắc: **task trong pool không chặn chờ một task khác của cùng pool.**

## 6. Tắt pool

Thread của pool là user thread: không tắt pool thì JVM không thoát.

```java
pool.shutdown();                                        // không nhận task mới, chạy nốt task đã nhận
if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
    pool.shutdownNow();                                 // gửi interrupt cho task đang chạy
}
```

| Method | Task đang chạy | Task trong hàng đợi |
|--------|----------------|---------------------|
| `shutdown()` | Chạy tiếp | Vẫn được chạy |
| `shutdownNow()` | Bị interrupt | Bị loại, trả về danh sách |
| `close()` (JDK 19+, dùng với try-with-resources) | Chờ xong | Vẫn được chạy |

`shutdownNow()` chỉ **gửi interrupt**. Task nuốt `InterruptedException` hoặc không kiểm tra cờ sẽ không dừng (bài 1).

Trong Spring, khai báo pool là bean (`ThreadPoolTaskExecutor`) để framework tắt hộ khi ứng dụng dừng.

## 7. Chạy theo lịch

```java
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

scheduler.schedule(() -> System.out.println("once, after 100 ms"), 100, TimeUnit.MILLISECONDS);

ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
        () -> System.out.println("tick"), 0, 50, TimeUnit.MILLISECONDS);

heartbeat.cancel(false);
scheduler.shutdown();
```

| Method | Khoảng cách tính từ |
|--------|---------------------|
| `scheduleAtFixedRate` | Lúc **bắt đầu** lần trước. Task chạy lâu hơn chu kỳ thì lần sau chạy ngay khi lần trước xong, không chồng lên nhau |
| `scheduleWithFixedDelay` | Lúc **kết thúc** lần trước |

Bẫy nghiêm trọng: nếu task định kỳ ném exception, **mọi lần chạy sau bị huỷ trong im lặng**. Luôn bọc toàn bộ thân
task trong `try/catch` có log.

## 8. Fork/Join và parallel stream

`ForkJoinPool` dành cho việc **tính toán chia để trị**: chia đôi bài toán, giải song song, gộp kết quả. Mỗi thread có
hàng đợi riêng và "trộm việc" của thread khác khi rảnh.

```java
static class SumTask extends RecursiveTask<Long> {
    ...
    @Override protected Long compute() {
        if (to - from <= 10_000) { /* đủ nhỏ: tính trực tiếp */ }
        int mid = (from + to) / 2;
        SumTask left = new SumTask(data, from, mid);
        left.fork();                                        // nửa trái chạy bất đồng bộ
        long right = new SumTask(data, mid, to).compute();  // nửa phải chạy ngay tại đây
        return left.join() + right;
    }
}

long sum = ForkJoinPool.commonPool().invoke(new SumTask(data, 0, data.length));
long sum2 = Arrays.stream(data).parallel().sum();           // parallel stream dùng common pool
```

Common pool dùng chung cho toàn JVM, mặc định có (số core − 1) thread: trên máy 8 core đo được `parallelism=7`.

- Parallel stream chỉ đáng dùng cho **tính toán trên dữ liệu lớn**. Với vài trăm phần tử nó chậm hơn tuần tự.
- **Không** chạy việc chặn (I/O) trong parallel stream hay common pool: bạn chiếm thread của mọi thư viện khác
  trong JVM, kể cả `CompletableFuture` không chỉ định executor.

## 9. Tự kiểm tra

1. Pool có core = 4, max = 16, hàng đợi `LinkedBlockingQueue` không giới hạn. Khi tải cao pool có mấy thread?
2. Nộp task bằng `submit()`, task ném exception, không ai gọi `get()`. Chuyện gì hiện ra trong log?
3. Vì sao pool tự kẹt không được `jstack` báo là deadlock?
4. Task định kỳ đang chạy tốt bỗng dừng hẳn, không có log lỗi. Nguyên nhân hay gặp nhất?

Đáp án ngắn: (1) 4: thread vượt core chỉ được tạo khi hàng đợi đầy, mà hàng đợi này không bao giờ đầy.
(2) Không gì cả; exception nằm trong `Future`. (3) Không có vòng chờ khoá nào; các thread chỉ đang chờ `Future`.
(4) Một lần chạy ném exception, và `ScheduledExecutorService` huỷ mọi lần chạy sau.

Bài tiếp theo: [06 — CompletableFuture](06-completablefuture.md).
