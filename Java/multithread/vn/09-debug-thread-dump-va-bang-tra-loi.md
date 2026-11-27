# 09 — Debug, thread dump và bảng tra lỗi

Mục tiêu: sau bài này bạn lấy và đọc được thread dump, nhận ra ba hình mẫu sự cố hay gặp nhất (deadlock, pool cạn,
vòng lặp không dừng), và có một bảng tra cùng checklist để review code đa luồng.

## 1. Vì sao debug đa luồng khác

Breakpoint và `println` làm đổi thời điểm chạy của các thread, nên lỗi thường biến mất đúng lúc bạn nhìn vào. Bản lỗi
của demo visibility dừng được bình thường khi thêm một dòng `println` vào vòng lặp, và cũng dừng được khi chạy với
`-Xint` (tắt JIT). Cả hai đều không phải cách sửa.

Công cụ chính vì thế là **thread dump**: ảnh chụp mọi thread đang làm gì tại một thời điểm, lấy từ ngoài vào, không
làm đổi hành vi của chương trình.

## 2. Lấy thread dump

| Lệnh | Ghi chú |
|------|---------|
| `jcmd -l` hoặc `jps -l` | Tìm PID của process Java |
| `jstack <pid>` | In dump ra màn hình |
| `jcmd <pid> Thread.print` | Tương đương `jstack`, là cách được khuyến nghị hiện nay |
| `jcmd <pid> Thread.dump_to_file -format=json <file>` | Bao gồm cả virtual thread (JDK 21) |
| `kill -3 <pid>` | Dump in ra `stdout` của chính process đó; dùng khi máy không có JDK đầy đủ |

```bash
jcmd <pid> Thread.print > dump-1.txt
```

```
52307:
2026-11-27 00:42:22
Full thread dump Java HotSpot(TM) 64-Bit Server VM (21.0.5+9-LTS-239 mixed mode, sharing):
```

**Lấy ba bản, cách nhau khoảng 5 giây.** Một bản chỉ là một khoảnh khắc. Thread đứng nguyên một chỗ qua cả ba bản mới
là thread bị kẹt; thread đổi vị trí là thread đang làm việc bình thường.

## 3. Đọc một thread trong dump

```
"transfer-A-to-B" #20 [29187] daemon prio=5 os_prio=31 cpu=0.16ms elapsed=2.22s tid=0x... nid=29187 waiting for monitor entry
   java.lang.Thread.State: BLOCKED (on object monitor)
	at DeadlockTransferDemo.transferBroken(DeadlockTransferDemo.java:35)
	- waiting to lock <0x000000070fc2ce78> (a DeadlockTransferDemo$Account)
	- locked <0x000000070fc2ce60> (a DeadlockTransferDemo$Account)
	at DeadlockTransferDemo.run(DeadlockTransferDemo.java:92)
```

| Phần | Nghĩa |
|------|-------|
| `"transfer-A-to-B"` | Tên thread. Lý do phải đặt tên có ý nghĩa |
| `daemon` | Là daemon thread |
| `cpu=0.16ms elapsed=2.22s` | Sống 2,22 giây nhưng chỉ dùng 0,16 ms CPU: gần như chỉ ngồi chờ |
| `Thread.State: BLOCKED` | Trạng thái (bài 1) |
| Dòng `at ...` đầu tiên | Đang đứng ở đâu. Đọc từ trên xuống: trên cùng là chỗ hiện tại |
| `- waiting to lock <0x...78>` | Đang xin khoá của object có địa chỉ này |
| `- locked <0x...60>` | Đang giữ khoá của object này |

Tỉ lệ `cpu` trên `elapsed` là manh mối nhanh nhất: gần 0 là đang chờ, gần 100% là đang quay.

Cách đọc theo trạng thái:

| Trạng thái + dòng đầu của stack | Thread đang |
|---------------------------------|-------------|
| `RUNNABLE`, trong code của bạn | Tính toán. Đứng yên một dòng qua nhiều dump: vòng lặp không dừng |
| `RUNNABLE`, trong `socketRead`, `SocketDispatcher.read` | Chờ dữ liệu từ mạng (database, HTTP). JVM vẫn ghi là RUNNABLE |
| `BLOCKED`, có `waiting to lock` | Chờ một khoá `synchronized`. Tìm thread có dòng `locked` cùng địa chỉ |
| `WAITING (parking)`, trong `ThreadPoolExecutor.getTask` | Thread pool đang rảnh, chờ việc. Bình thường |
| `WAITING (parking)`, trong `FutureTask.get`, `CompletableFuture.join` | Chờ kết quả của một task khác |
| `WAITING (parking)`, trong `ReentrantLock.lock` | Chờ một `ReentrantLock` |
| `WAITING (on object monitor)`, trong `Object.wait` | Chờ `notify` |
| `TIMED_WAITING`, trong `Thread.sleep` | Ngủ |

## 4. Ba hình mẫu sự cố

### Hình mẫu 1: deadlock

Chạy `java -DhangSeconds=60 -cp out DeadlockTransferDemo broken`, rồi `jstack <pid>`. Cuối dump, JVM tự kết luận:

```
Found one Java-level deadlock:
=============================
"transfer-A-to-B":
  waiting to lock monitor 0x0000600001e4c000 (object 0x000000070fc2ce78, a DeadlockTransferDemo$Account),
  which is held by "transfer-B-to-A"

"transfer-B-to-A":
  waiting to lock monitor 0x0000600001e48270 (object 0x000000070fc2ce60, a DeadlockTransferDemo$Account),
  which is held by "transfer-A-to-B"

Java stack information for the threads listed above:
===================================================
"transfer-A-to-B":
	at DeadlockTransferDemo.transferBroken(DeadlockTransferDemo.java:35)
	- waiting to lock <0x000000070fc2ce78> (a DeadlockTransferDemo$Account)
	- locked <0x000000070fc2ce60> (a DeadlockTransferDemo$Account)
...
"transfer-B-to-A":
	at DeadlockTransferDemo.transferBroken(DeadlockTransferDemo.java:35)
	- waiting to lock <0x000000070fc2ce60> (a DeadlockTransferDemo$Account)
	- locked <0x000000070fc2ce78> (a DeadlockTransferDemo$Account)

Found 1 deadlock.
```

Đọc chéo hai địa chỉ: thread 1 giữ `...60` xin `...78`; thread 2 giữ `...78` xin `...60`. Đó là vòng tròn. Dump cho
luôn dòng code (`:35`) và kiểu object bị khoá, đủ để tìm chỗ sửa.

Việc đầu tiên khi ứng dụng treo: tìm chuỗi `Found one Java-level deadlock` trong dump. JVM phát hiện được deadlock
của `synchronized` và của `ReentrantLock`.

### Hình mẫu 2: pool cạn, mọi thread cùng chờ

Chạy `java -DwaitSeconds=60 -cp out ThreadPoolStarvationDeadlockDemo broken`, rồi `jstack <pid>`:

```
"pool-1-thread-1" #20 [29955] prio=5 os_prio=31 cpu=0.35ms elapsed=2.17s ... waiting on condition
   java.lang.Thread.State: WAITING (parking)
	at jdk.internal.misc.Unsafe.park(java.base@21.0.5/Native Method)
	- parking to wait for  <0x000000070fc02b50> (a java.util.concurrent.FutureTask)
	at java.util.concurrent.locks.LockSupport.park(java.base@21.0.5/LockSupport.java:221)
	at java.util.concurrent.FutureTask.awaitDone(java.base@21.0.5/FutureTask.java:500)
	at java.util.concurrent.FutureTask.get(java.base@21.0.5/FutureTask.java:190)
	at ThreadPoolStarvationDeadlockDemo.processOrder(ThreadPoolStarvationDeadlockDemo.java:54)
	...
	at java.util.concurrent.ThreadPoolExecutor.runWorker(java.base@21.0.5/ThreadPoolExecutor.java:1144)
```

Dump này **không có** dòng "Found deadlock": không có khoá nào tham gia. Dấu hiệu nhận biết là hình mẫu:

- **Mọi** thread của một pool ở cùng một trạng thái, cùng một stack.
- Stack đi từ `ThreadPoolExecutor.runWorker` (thread của pool) lên tới `FutureTask.get` (đang chờ một task khác).

Cùng hình mẫu đó, chỉ khác dòng trên cùng, cho các nguyên nhân khác:

| Mọi thread của pool cùng đứng ở | Nguyên nhân |
|---------------------------------|-------------|
| `FutureTask.get`, `CompletableFuture.join` | Task chờ subtask của cùng pool (bài 5) |
| `socketRead` | Dịch vụ phía sau chậm hoặc treo, thiếu timeout |
| `HikariPool.getConnection` hoặc tương tự | Hết connection database: pool connection nhỏ hơn nhu cầu, hoặc connection bị rò |
| `waiting to lock` cùng một địa chỉ | Tranh chấp một khoá; tìm thread đang giữ và xem nó đang làm gì |

### Hình mẫu 3: vòng lặp không dừng

Chạy `java -cp out VisibilityStopFlagDemo broken`, lấy dump trong 3 giây nó còn chạy:

```
"spinner" #20 [28931] daemon prio=5 os_prio=31 cpu=2169.71ms elapsed=2.17s ... runnable
   java.lang.Thread.State: RUNNABLE
	at VisibilityStopFlagDemo.lambda$main$0(VisibilityStopFlagDemo.java:33)
```

`cpu=2169ms` trên `elapsed=2.17s`: thread này dùng trọn một core suốt thời gian sống. `RUNNABLE`, trong code của bạn,
và đứng nguyên một dòng qua mọi bản dump.

Tìm thread ăn CPU trong một process thật:

```bash
top -H -p <pid>          # Linux: liệt kê theo thread, lấy TID của thread ăn CPU
printf '%x\n' <tid>      # đổi sang hệ 16
jstack <pid> | grep -A 20 'nid=0x<hex>'
```

Trên JDK 21, trường `cpu=` trong dump thường đã đủ: so sánh giá trị đó giữa hai bản dump là biết thread nào đang ăn CPU.

## 5. Quy trình khi gặp sự cố

```
Ứng dụng treo hoặc chậm
        │
        ▼
CPU của process cao hay thấp?
        │
   ┌────┴─────────────────────────────┐
   ▼ thấp (gần 0)                     ▼ cao
Lấy 3 thread dump                  Lấy 3 thread dump
   │                                  │
   ├─ có "Found deadlock"?            ├─ thread RUNNABLE đứng yên trong code của bạn
   │     → hình mẫu 1                 │     → vòng lặp không dừng (visibility), livelock
   ├─ mọi thread của pool chờ         ├─ rất nhiều thread GC đang chạy
   │  cùng một chỗ?                   │     → vấn đề bộ nhớ, xem Java/memory
   │     → hình mẫu 2                 └─ nhiều thread RUNNABLE ở nhiều chỗ khác nhau
   └─ chờ I/O (socketRead)?                 → quá tải thật, hoặc thuật toán kém
         → dịch vụ phía sau, timeout
```

Kết quả **sai** (số liệu lệch, dữ liệu trùng) thì thread dump không giúp: đó là race condition, tìm bằng cách đọc code
theo checklist ở mục 8.

## 6. Công cụ khác

| Công cụ | Dùng để |
|---------|---------|
| `ThreadMXBean.findDeadlockedThreads()` | Tự phát hiện deadlock trong code (health check). [DeadlockTransferDemo](../demos/DeadlockTransferDemo.java) dùng cách này |
| `ThreadPoolExecutor.toString()` | Xem nhanh `pool size`, `active threads`, `queued tasks`, `completed tasks` |
| Metrics của pool: `getActiveCount()`, `getQueue().size()` | Đưa lên dashboard; hàng đợi tăng dần là cảnh báo sớm |
| JFR: `jcmd <pid> JFR.start duration=60s filename=rec.jfr` | Ghi tranh chấp khoá, thread bị chặn lâu, pinning của virtual thread; mở bằng JDK Mission Control |
| VisualVM, IntelliJ debugger (tab Threads) | Xem thread trực quan khi phát triển |
| `-Djdk.tracePinnedThreads=full` (JDK 21–23) | In stack khi virtual thread bị ghim |
| jcstress | Kiểm thử tính đúng của code đồng bộ mức thấp |

### Viết test cho code đa luồng

Test không chứng minh được code đa luồng đúng, nhưng làm tăng xác suất lộ lỗi:

- Dùng `CountDownLatch` làm cổng xuất phát để mọi thread vào đoạn nghi ngờ cùng lúc (bài 7).
- Nhiều thread hơn số core, lặp lại nhiều lần, kiểm tra **bất biến** ở cuối (tổng số dư không đổi, số đếm bằng kỳ vọng).
- Luôn đặt timeout cho test, nếu không deadlock làm treo cả pipeline.
- Test xanh không có nghĩa là đúng. Demo visibility "đúng" khi tắt JIT.

## 7. Bảng tra lỗi thường gặp

| Triệu chứng | Nguyên nhân hay gặp | Cách xử lý |
|-------------|---------------------|-----------|
| Số đếm, số dư, tồn kho lệch khi tải cao | `x++`, đọc rồi ghi, không đồng bộ | Atomic, `merge`, hoặc khoá (bài 2, 4) |
| Thỉnh thoảng tạo trùng bản ghi, khởi tạo hai lần | Check-then-act | `computeIfAbsent`, `putIfAbsent`, ràng buộc unique ở database |
| Thread không dừng, không nhận giá trị mới | Thiếu `volatile` | `volatile`, hoặc đọc trong khoá (bài 2) |
| Lỗi biến mất khi debug hoặc thêm log | Visibility hoặc race | Không tin vào "hết lỗi"; tìm biến dùng chung chưa được bảo vệ |
| Ứng dụng treo, CPU gần 0, có "Found deadlock" | Hai khoá lấy ngược thứ tự | Thứ tự khoá toàn cục, `tryLock` có timeout (bài 3) |
| Treo, CPU gần 0, mọi thread của pool trong `FutureTask.get` | Task chờ subtask cùng pool | Tách pool, hoặc ghép không chặn (bài 5, 6) |
| Treo dần khi dịch vụ phía sau chậm | Thiếu timeout; pool dùng chung | Timeout cho mọi lời gọi ra ngoài; mỗi phụ thuộc một pool |
| `OutOfMemoryError` với hàng đợi rất lớn trong heap dump | Hàng đợi không giới hạn của `newFixedThreadPool` | Hàng đợi có giới hạn + chính sách từ chối (bài 5) |
| `OutOfMemoryError: unable to create native thread` | Tạo thread không giới hạn (`new Thread` cho mỗi việc, `newCachedThreadPool`) | Pool có trần |
| `RejectedExecutionException` | Pool và hàng đợi đều đầy, hoặc nộp task sau `shutdown()` | Xử lý như tín hiệu quá tải; kiểm tra vòng đời pool |
| Task chạy nền lỗi mà không có log | `submit()` rồi không gọi `get()`; `CompletableFuture` thiếu xử lý lỗi | `try/catch` có log trong task; `exceptionally`/`handle` |
| Task định kỳ tự dừng | Một lần chạy ném exception | Bọc toàn bộ thân task trong `try/catch` |
| JVM không thoát sau khi `main` xong | Pool chưa `shutdown()` | `shutdown()`, try-with-resources, hoặc để framework quản lý |
| `shutdownNow()` không dừng được task | Task nuốt `InterruptedException` | Bật lại cờ interrupt và thoát (bài 1) |
| `ConcurrentModificationException` | Sửa collection khi đang duyệt, hoặc `ArrayList`/`HashMap` dùng từ nhiều thread | Concurrent collection; duyệt trên bản sao |
| `HashMap.get` trả `null` cho key đang có; CPU 100% trong `HashMap` | `HashMap` bị nhiều thread ghi | `ConcurrentHashMap` |
| `IllegalMonitorStateException` | Gọi `wait`/`notify` ngoài `synchronized`, hoặc `unlock` khoá không giữ | Gọi trong khối khoá của đúng object |
| Dữ liệu của user này hiện ở request của user khác | `ThreadLocal` không `remove()` trong pool; field thay đổi được trong bean singleton | `remove()` trong `finally`; bỏ field trạng thái (bài 4) |
| Kết quả ngày tháng sai lung tung | `SimpleDateFormat` dùng chung | `DateTimeFormatter` (bất biến) |
| `CompletableFuture` chậm bất thường, parallel stream treo | Việc chặn chạy trên common pool | Truyền executor riêng (bài 6) |
| Chuyển sang virtual thread, thông lượng giảm | Pinning do `synchronized` quanh I/O (JDK 21–23) | `ReentrantLock`, nâng JDK, cập nhật driver (bài 8) |
| Chuyển sang virtual thread, database báo quá nhiều kết nối | Mất giới hạn ngầm của kích thước pool | `Semaphore`, connection pool (bài 8) |
| Thêm thread mà không nhanh hơn | Việc CPU-bound vượt số core; tranh chấp một khoá; phần tuần tự lớn | Đo trước; thu hẹp vùng khoá; số thread bằng số core |

## 8. Checklist review code đa luồng

**Trạng thái dùng chung**

- [ ] Với mỗi field thay đổi được: nó có bị hơn một thread chạm tới không? Nếu có, cái gì bảo vệ nó?
- [ ] Bean singleton (service, controller) không có field trạng thái thay đổi được.
- [ ] Đọc và ghi cùng một biến đi qua cùng một cơ chế (cùng khoá, hoặc `volatile`, hoặc atomic).
- [ ] Không có thao tác ghép (`get` rồi `put`, kiểm tra rồi hành động) trên concurrent collection.
- [ ] Object chia sẻ giữa các thread là bất biến ở mọi chỗ có thể.

**Khoá**

- [ ] Khoá trên object `private final`, không khoá trên `this` công khai, `String`, hay kiểu boxed.
- [ ] `unlock()` nằm trong `finally`.
- [ ] Không gọi I/O hay code lạ khi đang giữ khoá.
- [ ] Chỗ nào lấy hơn một khoá đều theo một thứ tự cố định.

**Thread pool và tác vụ bất đồng bộ**

- [ ] Không có `new Thread()` trong code nghiệp vụ.
- [ ] Hàng đợi của pool có giới hạn; chính sách từ chối được chọn có chủ đích.
- [ ] Thread có tên.
- [ ] Task không chặn chờ task khác của cùng pool.
- [ ] Mọi lời gọi ra ngoài có timeout; mọi `get()`/`await()` có timeout.
- [ ] Việc chặn không chạy trên common pool.
- [ ] Mọi `Future`/`CompletableFuture` đều có người xử lý lỗi.
- [ ] Pool được tắt khi ứng dụng dừng.

**Interrupt và dọn dẹp**

- [ ] Không nuốt `InterruptedException`.
- [ ] `ThreadLocal` được `remove()` trong `finally`.
- [ ] `release()`, `countDown()` nằm trong `finally`.

Bài tiếp theo: [10 — Thực hành với demo](10-thuc-hanh-voi-demo.md).
