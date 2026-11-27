# 10 — Thực hành: tìm và fix qua 8 demo

Mỗi demo đi theo cùng một vòng: **chạy bản lỗi → quan sát → đọc code tìm nguyên nhân → chạy bản fix → kiểm chứng**.
Output mẫu lấy từ JDK 21.0.5 trên máy 8 core. Con số của bạn sẽ khác; riêng các demo về race condition cho ra số khác
nhau ở **mỗi lần chạy**, và đó chính là một phần của bài học.

## Chuẩn bị

```bash
cd Java/multithread
javac -d out demos/*.java
```

Một số demo cần hai terminal: **T1** chạy demo, **T2** lấy thread dump. Tìm PID bằng dòng `PID=...` demo in ra,
hoặc `jcmd -l`.

| # | Demo | Chế độ | Bài lý thuyết |
|---|------|--------|---------------|
| 1 | `RaceConditionCounterDemo` | `broken` / `fixed` | [02](02-race-condition-visibility-va-java-memory-model.md) |
| 2 | `VisibilityStopFlagDemo` | `broken` / `fixed` | [02](02-race-condition-visibility-va-java-memory-model.md) |
| 3 | `DeadlockTransferDemo` | `broken` / `fixed` | [03](03-synchronized-lock-va-deadlock.md) |
| 4 | `CheckThenActConcurrentMapDemo` | `broken` / `fixed` | [04](04-atomic-concurrent-collection-va-chien-luoc-thread-safe.md) |
| 5 | `ThreadPoolStarvationDeadlockDemo` | `broken` / `fixed` | [05](05-thread-pool-va-executor.md) |
| 6 | `CompletableFutureCompositionDemo` | `sequential` / `parallel` / `failure` | [06](06-completablefuture.md) |
| 7 | `SemaphoreConcurrencyLimitDemo` | `broken` / `fixed` | [07](07-synchronizer-va-phoi-hop-thread.md) |
| 8 | `VirtualThreadsBlockingIoDemo` | `platform` / `virtual` | [08](08-virtual-thread.md) |

---

## Demo 1 — Race condition: mất cập nhật

File: [RaceConditionCounterDemo.java](../demos/RaceConditionCounterDemo.java)

### Chạy bản lỗi

```bash
java -cp out RaceConditionCounterDemo broken
java -cp out RaceConditionCounterDemo broken
```

```
mode=broken threads=8 incrementsPerThread=1,000,000
expected=8,000,000 actual=1,626,740 lost=6,373,260
RESULT: WRONG (lost updates)

mode=broken threads=8 incrementsPerThread=1,000,000
expected=8,000,000 actual=1,963,442 lost=6,036,558
RESULT: WRONG (lost updates)
```

Quan sát: không có exception. Kết quả sai, và sai **khác nhau** mỗi lần. Đó là dấu hiệu nhận dạng của race condition.

### Tìm nguyên nhân

```java
static int plainCounter = 0;
...
plainCounter++;
```

Đặt ba câu hỏi của bài 2: biến này có dùng chung không (có, `static`), có thay đổi được không (có), có thread ghi không
(cả 8). Không có gì bảo vệ. `++` là đọc, cộng, ghi; hai thread đọc cùng giá trị thì một lần cộng bị mất.

### Chạy bản fix

```bash
java -cp out RaceConditionCounterDemo fixed
```

```
expected=8,000,000 actual=8,000,000 lost=0
RESULT: CORRECT
```

`AtomicInteger.incrementAndGet()` thực hiện cả ba bước như một thao tác nguyên tử.

### Thử thêm

- Đổi `static int plainCounter` thành `static volatile int plainCounter` rồi chạy `broken`. Vẫn sai: `volatile`
  không làm `++` thành nguyên tử.
- Đổi `THREADS` thành 1. Bản lỗi ra kết quả đúng: không có dùng chung thì không có lỗi.

---

## Demo 2 — Visibility: thread không thấy cờ dừng

File: [VisibilityStopFlagDemo.java](../demos/VisibilityStopFlagDemo.java)

### Chạy bản lỗi

```bash
java -cp out VisibilityStopFlagDemo broken
```

```
mode=broken
main: set running=false
RESULT: WRONG (worker is still spinning 3s after the flag changed)
```

`main` đã đặt cờ thành `false`, nhưng dòng `worker: saw the stop flag...` không bao giờ xuất hiện.

### Trace (T2, trong 3 giây demo còn chạy)

```bash
jstack $(jcmd -l | grep VisibilityStopFlagDemo | cut -d' ' -f1) | grep -A 3 '"spinner"'
```

```
"spinner" #20 [28931] daemon prio=5 os_prio=31 cpu=2169.71ms elapsed=2.17s ... runnable
   java.lang.Thread.State: RUNNABLE
	at VisibilityStopFlagDemo.lambda$main$0(VisibilityStopFlagDemo.java:33)
```

`RUNNABLE`, `cpu` xấp xỉ `elapsed`: worker đang quay hết công suất và không hề đọc lại cờ.

### Chứng minh đây là do JIT

```bash
java -Xint -cp out VisibilityStopFlagDemo broken
```

```
mode=broken
main: set running=false
worker: saw the stop flag after 197,008,279 iterations
RESULT: CORRECT (worker stopped)
```

`-Xint` tắt JIT, vòng lặp chạy ở chế độ thông dịch và đọc lại biến ở mỗi vòng. Cùng một file class, cùng một bug, lần
này "đúng". Bài học: code đa luồng sai có thể chạy đúng trong test, trong debugger, trên máy bạn, rồi sai ở production.

### Chạy bản fix

```bash
java -cp out VisibilityStopFlagDemo fixed
```

```
mode=fixed
main: set running=false
worker: saw the stop flag after 1,753,059,506 iterations
RESULT: CORRECT (worker stopped)
```

Khác biệt duy nhất trong code là từ khoá `volatile`.

---

## Demo 3 — Deadlock

File: [DeadlockTransferDemo.java](../demos/DeadlockTransferDemo.java)

### Chạy bản lỗi

```bash
java -cp out DeadlockTransferDemo broken
```

```
PID=52110 mode=broken
DEADLOCK detected by ThreadMXBean:
  "transfer-A-to-B" BLOCKED, waiting for lock held by "transfer-B-to-A"
  "transfer-B-to-A" BLOCKED, waiting for lock held by "transfer-A-to-B"
RESULT: WRONG (deadlock, transfers never finish)
```

Demo tự phát hiện bằng `ThreadMXBean.findDeadlockedThreads()` rồi thoát. Hai thread là daemon nên JVM thoát được.

### Trace bằng jstack

T1, giữ process sống 60 giây:

```bash
java -DhangSeconds=60 -cp out DeadlockTransferDemo broken
```

T2:

```bash
jstack <pid> | sed -n '/Found one Java-level deadlock/,$p'
```

```
Found one Java-level deadlock:
=============================
"transfer-A-to-B":
  waiting to lock monitor 0x0000600001e4c000 (object 0x000000070fc2ce78, a DeadlockTransferDemo$Account),
  which is held by "transfer-B-to-A"

"transfer-B-to-A":
  waiting to lock monitor 0x0000600001e48270 (object 0x000000070fc2ce60, a DeadlockTransferDemo$Account),
  which is held by "transfer-A-to-B"
...
"transfer-A-to-B":
	at DeadlockTransferDemo.transferBroken(DeadlockTransferDemo.java:35)
	- waiting to lock <0x000000070fc2ce78> (a DeadlockTransferDemo$Account)
	- locked <0x000000070fc2ce60> (a DeadlockTransferDemo$Account)
...
Found 1 deadlock.
```

Dump chỉ thẳng dòng 35 của `transferBroken`: khối `synchronized (to)` bên trong.

### Tìm nguyên nhân

Thứ tự khoá phụ thuộc thứ tự tham số: `transfer(A, B)` khoá A rồi B, `transfer(B, A)` khoá B rồi A.
`Thread.sleep(100)` giữa hai lần khoá chỉ để deadlock xảy ra ở mọi lần chạy; bỏ nó đi thì deadlock vẫn có thể xảy ra,
chỉ là hiếm, tức là loại lỗi tệ nhất.

### Chạy bản fix

```bash
java -cp out DeadlockTransferDemo fixed
```

```
PID=52113 mode=fixed
balances: A=1100 B=900 total=2000
RESULT: CORRECT (both transfers finished)
```

Luôn khoá tài khoản có `id` nhỏ hơn trước. Tổng số dư vẫn 2000: bất biến được giữ.

---

## Demo 4 — Check-then-act trên ConcurrentHashMap

File: [CheckThenActConcurrentMapDemo.java](../demos/CheckThenActConcurrentMapDemo.java)

```bash
java -cp out CheckThenActConcurrentMapDemo broken
java -cp out CheckThenActConcurrentMapDemo fixed
```

```
mode=broken threads=8 hitsPerThread=200,000
expected=1,600,000 actual=231,637 lost=1,368,363
RESULT: WRONG (lost updates)

mode=fixed threads=8 hitsPerThread=200,000
expected=1,600,000 actual=1,600,000 lost=0
RESULT: CORRECT
```

Quan sát: bản lỗi dùng `ConcurrentHashMap`, một class thread-safe, mà vẫn mất hơn 85% số lượt đếm.

Nguyên nhân: `get` rồi `put` là hai lời gọi. Mỗi lời gọi an toàn; khoảng giữa thì không.
Fix: `hits.merge(PAGE, 1, Integer::sum)` làm cả việc đọc, cộng, ghi trong một thao tác nguyên tử.

So với demo 1: cùng một loại lỗi (read-modify-write), chỉ khác là ẩn sau một API trông an toàn.

---

## Demo 5 — Pool tự kẹt

File: [ThreadPoolStarvationDeadlockDemo.java](../demos/ThreadPoolStarvationDeadlockDemo.java)

### Chạy bản lỗi

```bash
java -cp out ThreadPoolStarvationDeadlockDemo broken
```

```
PID=52329 mode=broken
orderPool state: java.util.concurrent.ThreadPoolExecutor@330bedb4[Running, pool size = 2, active threads = 2, queued tasks = 2, completed tasks = 0]
RESULT: WRONG (no order finished in time: every pool thread waits for a queued subtask)
```

### Trace

T1: `java -DwaitSeconds=60 -cp out ThreadPoolStarvationDeadlockDemo broken`

T2:

```bash
jstack <pid> | grep -c 'deadlock'
jstack <pid> | sed -n '/^"pool-1-thread-1"/,/^$/p'
```

```
0
"pool-1-thread-1" #20 [29955] prio=5 os_prio=31 cpu=0.35ms elapsed=2.17s ... waiting on condition
   java.lang.Thread.State: WAITING (parking)
	- parking to wait for  <0x000000070fc02b50> (a java.util.concurrent.FutureTask)
	at java.util.concurrent.FutureTask.get(java.base@21.0.5/FutureTask.java:190)
	at ThreadPoolStarvationDeadlockDemo.processOrder(ThreadPoolStarvationDeadlockDemo.java:54)
	...
	at java.util.concurrent.ThreadPoolExecutor.runWorker(java.base@21.0.5/ThreadPoolExecutor.java:1144)
```

`jstack` báo **0 deadlock**. So với demo 3: ở đó JVM tự chỉ ra vòng khoá; ở đây bạn phải tự nhận ra hình mẫu
"thread của pool đang chờ một `FutureTask`", cộng với `active threads = pool size` và `queued tasks > 0`.

### Chạy bản fix

```bash
java -cp out ThreadPoolStarvationDeadlockDemo fixed
```

```
PID=... mode=fixed
order 1 -> paid
order 2 -> paid
RESULT: CORRECT (all orders completed)
```

Subtask thanh toán chạy ở pool riêng.

### Thử thêm

Trong bản lỗi, đổi `newFixedThreadPool(2)` của `orderPool` thành `newFixedThreadPool(3)`. Demo chạy đúng. Lỗi không
biến mất: nó chỉ chờ đến khi có 3 đơn hàng cùng lúc. Đây là lý do loại lỗi này chỉ hiện ra dưới tải.

---

## Demo 6 — CompletableFuture

File: [CompletableFutureCompositionDemo.java](../demos/CompletableFutureCompositionDemo.java)

```bash
java -cp out CompletableFutureCompositionDemo sequential
java -cp out CompletableFutureCompositionDemo parallel
java -cp out CompletableFutureCompositionDemo failure
```

```
mode=sequential
Dashboard[user=an, orders=[order-1, order-2], recommendations=[book-7, book-9]]
elapsed=1212 ms

mode=parallel
Dashboard[user=an, orders=[order-1, order-2], recommendations=[book-7, book-9]]
elapsed=510 ms

mode=failure
recommendations failed: java.lang.IllegalStateException: recommendation service is down -> using fallback
Dashboard[user=an, orders=[order-1, order-2], recommendations=[bestseller-1]]
elapsed=519 ms
```

Đọc kết quả:

- `sequential`: 300 + 400 + 500 = 1200 ms.
- `parallel`: bằng lời gọi chậm nhất, 500 ms. Kết quả giống hệt.
- `failure`: dịch vụ gợi ý lỗi, trang vẫn trả về trong 519 ms với danh sách dự phòng.

### Thử thêm

- Trong `loadParallel`, thêm `.join()` ngay sau `supplyAsync` đầu tiên (đổi kiểu biến `user` thành `String` và bọc lại
  bằng `CompletableFuture.completedFuture`). Thời gian quay về gần 800 ms: bạn vừa biến song song thành tuần tự.
- Bỏ khối `.exceptionally(...)` rồi chạy `failure`. Cả trang lỗi với `CompletionException`.
- Đổi `ioPool` thành `Executors.newFixedThreadPool(1)` rồi chạy `parallel`. Thời gian về 1200 ms: "bất đồng bộ" không
  có nghĩa là song song nếu pool không đủ thread.

---

## Demo 7 — Semaphore và CountDownLatch

File: [SemaphoreConcurrencyLimitDemo.java](../demos/SemaphoreConcurrencyLimitDemo.java)

```bash
java -cp out SemaphoreConcurrencyLimitDemo broken
java -cp out SemaphoreConcurrencyLimitDemo fixed
```

```
mode=broken tasks=20 downstreamCapacity=3
peakConcurrentCalls=20 elapsed=109 ms
RESULT: WRONG (downstream overloaded)

mode=fixed tasks=20 downstreamCapacity=3
peakConcurrentCalls=3 elapsed=732 ms
RESULT: CORRECT (downstream never saw more than its capacity)
```

Bản lỗi nhanh hơn 7 lần, và sai: nó đẩy 20 lời gọi cùng lúc vào một dịch vụ chỉ chịu được 3. "Nhanh hơn" không phải
lúc nào cũng là "tốt hơn".

Tìm trong code ba vai trò: `startGate` (latch đếm 1, dồn 20 task xuất phát cùng lúc), `allDone` (latch đếm 20, `main`
chờ tất cả), `permits` (semaphore 3 giấy phép).

### Thử thêm

- Chuyển `permits.release()` ra khỏi `finally` và cho `callDownstream` ném exception ở lần gọi thứ 5. Giấy phép rò dần
  và demo treo.
- Bỏ `startGate.await()`. `peakConcurrentCalls` của bản lỗi có thể thấp hơn 20: không còn gì bảo đảm các task va vào
  nhau. Đó là lý do test đa luồng cần cổng xuất phát.

---

## Demo 8 — Virtual thread

File: [VirtualThreadsBlockingIoDemo.java](../demos/VirtualThreadsBlockingIoDemo.java)

```bash
java -cp out VirtualThreadsBlockingIoDemo platform
java -cp out VirtualThreadsBlockingIoDemo virtual
```

```
mode=platform(pool=100) tasks=2,000 blockMillis=200
completed=2,000 elapsed=4075 ms

mode=virtual tasks=2,000 blockMillis=200
completed=2,000 elapsed=230 ms
```

Tính lại bằng tay: 2000 việc / 100 thread = 20 lượt, mỗi lượt 200 ms, ra 4 giây. Với virtual thread, cả 2000 việc chờ
cùng lúc.

### Thử thêm

- Tăng `TASKS` lên 100.000 và chạy `virtual`. Đo được 554 ms cho 100.000 việc. Thử điều tương tự với
  `Executors.newCachedThreadPool()` (một platform thread cho mỗi việc) để thấy giới hạn của platform thread.
- Thay `Thread.sleep(BLOCK_MILLIS)` bằng một vòng lặp tính toán 200 ms. Virtual thread không còn lợi thế: việc
  CPU-bound không nhả carrier.

---

## Tổng kết

| Demo | Triệu chứng | Công cụ tìm ra | Cách sửa |
|------|-------------|----------------|----------|
| 1 | Số sai, mỗi lần một khác | Đọc code: biến dùng chung không được bảo vệ | Atomic |
| 2 | Thread không dừng, CPU 100% | Thread dump: `RUNNABLE`, đứng yên | `volatile` |
| 3 | Treo, CPU 0% | Thread dump: "Found deadlock" | Thứ tự khoá |
| 4 | Số sai dù dùng class thread-safe | Đọc code: thao tác ghép | `merge`, `computeIfAbsent` |
| 5 | Treo, CPU 0%, không có deadlock | Thread dump: thread của pool chờ `FutureTask`; trạng thái pool | Tách pool, không chặn |
| 6 | Chậm | Đo thời gian | Chạy song song các việc độc lập |
| 7 | Dịch vụ phía sau quá tải | Đo mức đồng thời | `Semaphore` |
| 8 | Thông lượng thấp dù CPU rảnh | Đo thời gian; số thread bị chặn | Virtual thread |

Hai nhóm lỗi, hai cách tìm:

- **Sai kết quả** (1, 4): thread dump không giúp. Tìm bằng cách rà từng biến dùng chung theo
  [checklist ở bài 9](09-debug-thread-dump-va-bang-tra-loi.md#8-checklist-review-code-đa-luồng).
- **Treo hoặc chậm** (2, 3, 5): thread dump là công cụ đầu tiên.

Bước tiếp theo: lấy một service thật bạn đang làm, liệt kê mọi field thay đổi được trong các bean singleton, mọi
thread pool cùng cấu hình hàng đợi của nó, và đi qua checklist ở bài 9.
