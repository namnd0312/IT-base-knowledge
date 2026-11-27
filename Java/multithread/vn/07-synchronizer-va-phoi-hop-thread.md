# 07 — Synchronizer và phối hợp thread

Mục tiêu: sau bài này bạn biết cách cho thread **chờ nhau có điều kiện** ("chờ đến khi cả 5 dịch vụ sẵn sàng",
"tối đa 3 lời gọi cùng lúc"), dùng đúng công cụ dựng sẵn, và hiểu `wait`/`notify` đủ để đọc code cũ.

Khoá (bài 3) trả lời "ai được vào". Synchronizer trả lời "khi nào được đi tiếp".

## 1. Bản đồ công cụ

| Cần | Công cụ |
|-----|---------|
| Chờ N việc hoàn thành, hoặc mở cổng cho nhiều thread cùng xuất phát | `CountDownLatch` |
| Giới hạn số thread cùng làm một việc | `Semaphore` |
| Nhiều thread chờ nhau tại một điểm hẹn, lặp lại nhiều vòng | `CyclicBarrier` |
| Chuyển việc giữa producer và consumer | `BlockingQueue` (bài 4) |
| Chờ một điều kiện tuỳ ý trên trạng thái riêng | `Condition`, hoặc `wait`/`notify` |

Thứ tự ưu tiên: dùng công cụ dựng sẵn trước. Tự viết `wait`/`notify` chỉ khi không cái nào khớp.

## 2. `CountDownLatch`

Một bộ đếm chỉ giảm. Thread gọi `await()` bị chặn cho tới khi bộ đếm về 0. **Dùng một lần**, không đặt lại được.

```java
CountDownLatch ready = new CountDownLatch(3);
for (int i = 0; i < 3; i++) {
    new Thread(ready::countDown).start();
}
boolean allReady = ready.await(5, TimeUnit.SECONDS);    // false nếu hết 5 giây mà chưa đủ
```

Hai cách dùng, cả hai đều có trong [SemaphoreConcurrencyLimitDemo.java](../demos/SemaphoreConcurrencyLimitDemo.java):

```java
CountDownLatch startGate = new CountDownLatch(1);      // cổng xuất phát: đếm 1, main mở một lần
CountDownLatch allDone = new CountDownLatch(TASKS);    // vạch đích: mỗi task giảm một

// trong mỗi task
startGate.await();          // mọi task đứng chờ ở đây
...
allDone.countDown();        // trong finally

// main
startGate.countDown();      // mở cổng: 20 task chạy cùng một thời điểm
allDone.await();            // chờ cả 20 xong
```

Lưu ý:

- `countDown()` phải nằm trong `finally`. Một task ném exception mà không giảm bộ đếm thì thread đang `await()` chờ mãi.
- Luôn dùng `await` có timeout trong code production.
- Cổng xuất phát là cách viết test đa luồng đáng tin: nó dồn mọi thread vào cùng một thời điểm để lộ race condition.

## 3. `Semaphore`

Giữ một số **giấy phép**. `acquire()` lấy một cái, hết thì chờ. `release()` trả lại. Dùng để giới hạn mức đồng thời.

Demo: 20 task gọi một dịch vụ phía sau chỉ chịu được 3 lời gọi cùng lúc.

```
mode=broken tasks=20 downstreamCapacity=3
peakConcurrentCalls=20 elapsed=109 ms
RESULT: WRONG (downstream overloaded)
```

Cả 20 lời gọi dội vào cùng lúc. Nhanh, và là cách đánh sập dịch vụ phía sau.

```java
Semaphore permits = new Semaphore(DOWNSTREAM_CAPACITY);

permits.acquire();              // chặn khi đã có 3 lời gọi đang chạy
try {
    callDownstream();
} finally {
    permits.release();          // luôn trả trong finally, nếu không giấy phép rò dần
}
```

```
mode=fixed tasks=20 downstreamCapacity=3
peakConcurrentCalls=3 elapsed=732 ms
RESULT: CORRECT (downstream never saw more than its capacity)
```

Chậm hơn (20 lời gọi, 3 cái một lượt, mỗi lượt 100 ms: 7 lượt), và đó là cái giá đúng: thông lượng được quyết định
bởi sức chứa thật của hệ thống phía sau.

Không muốn chờ vô hạn thì thử lấy có thời hạn và từ chối sớm:

```java
if (permits.tryAcquire(500, TimeUnit.MILLISECONDS)) {
    try { ... } finally { permits.release(); }
} else {
    // quá tải: trả lỗi "bận" thay vì xếp hàng mãi
}
```

| | `Semaphore` | `synchronized` / `Lock` |
|---|---|---|
| Số thread được vào | N | 1 |
| Ai được nhả | Bất kỳ thread nào | Chỉ thread đang giữ |
| Vào lại (reentrant) | Không: gọi `acquire` hai lần tốn hai giấy phép | Có |

`Semaphore` còn là cách giới hạn đồng thời chuẩn khi dùng virtual thread, vì ở đó không còn kích thước pool để làm
giới hạn (bài 8).

## 4. `CyclicBarrier`

Điểm hẹn cho một nhóm cố định: mỗi thread gọi `await()` và chờ cho tới khi **đủ cả nhóm**; khi đó tất cả cùng đi tiếp,
và barrier tự đặt lại cho vòng sau.

```java
CyclicBarrier barrier = new CyclicBarrier(3, () -> System.out.println("all 3 arrived, round starts"));
for (int i = 0; i < 3; i++) {
    new Thread(() -> {
        try {
            barrier.await();
        } catch (InterruptedException | BrokenBarrierException e) { }
    }).start();
}
```

| | `CountDownLatch` | `CyclicBarrier` |
|---|---|---|
| Ai chờ ai | Một hay nhiều thread chờ **sự kiện** (bộ đếm về 0) | Các thread trong nhóm chờ **lẫn nhau** |
| Dùng lại | Không | Có |
| Một thread lỗi | Phải tự bảo đảm `countDown` | Barrier "vỡ": mọi thread đang chờ nhận `BrokenBarrierException` |
| Ví dụ | Chờ khởi động xong; chờ lô task xong | Mô phỏng theo từng bước; tính toán theo vòng |

Còn hai công cụ ít gặp hơn: `Phaser` (như barrier nhưng số thành viên thay đổi được, nhiều pha) và `Exchanger`
(hai thread đổi dữ liệu cho nhau tại một điểm hẹn).

## 5. `wait` / `notify`

Đây là cơ chế gốc mà mọi công cụ trên xây lên. Ngày nay hiếm khi phải tự viết, nhưng cần hiểu để đọc code cũ và trả
lời phỏng vấn.

| Method | Làm gì |
|--------|--------|
| `lock.wait()` | **Nhả khoá** `lock` và ngủ cho tới khi được đánh thức; khi dậy thì lấy lại khoá rồi mới chạy tiếp |
| `lock.notify()` | Đánh thức **một** thread đang `wait` trên `lock` (không chọn được cái nào) |
| `lock.notifyAll()` | Đánh thức **tất cả** |

Cả ba chỉ được gọi khi đang giữ khoá của chính object đó (bên trong `synchronized`), nếu không sẽ ném
`IllegalMonitorStateException`.

Bộ đệm có giới hạn viết đúng:

```java
static class BoundedBuffer<T> {
    private final Queue<T> items = new ArrayDeque<>();
    private final int capacity;

    BoundedBuffer(int capacity) { this.capacity = capacity; }

    public synchronized void put(T item) throws InterruptedException {
        while (items.size() == capacity) {      // while, KHÔNG phải if
            wait();
        }
        items.add(item);
        notifyAll();
    }

    public synchronized T take() throws InterruptedException {
        while (items.isEmpty()) {
            wait();
        }
        T item = items.remove();
        notifyAll();
        return item;
    }
}
```

### Ba quy tắc

1. **Luôn `wait` trong vòng `while`, không dùng `if`.** Khi thread tỉnh dậy, điều kiện có thể đã sai trở lại: thread
   khác đã lấy mất phần tử trong lúc nó chờ lấy lại khoá, và JVM còn được phép đánh thức "vô cớ" (spurious wakeup).
   Dùng `if` thì `take()` sẽ gọi `remove()` trên hàng đợi rỗng.
2. **Ưu tiên `notifyAll` hơn `notify`.** `notify` có thể đánh thức nhầm thread (một producer đánh thức một producer
   khác trong khi bộ đệm vẫn đầy), và tín hiệu coi như mất: mọi thread ngủ mãi.
3. **Kiểm tra điều kiện, chờ, và thay đổi trạng thái phải cùng một khoá.** Nếu không, tín hiệu có thể đến đúng vào
   khe giữa "kiểm tra" và "bắt đầu chờ" rồi bị bỏ lỡ.

| | `wait()` | `Thread.sleep()` |
|---|---|---|
| Nhả khoá | Có | **Không** |
| Thức dậy khi | Được `notify`, hết giờ, bị interrupt | Hết giờ, bị interrupt |
| Gọi ở đâu | Trong `synchronized` | Bất kỳ |

## 6. `Condition`

Bản tương ứng của `wait`/`notify` cho `ReentrantLock`, với một ưu điểm: **nhiều hàng chờ riêng trên cùng một khoá**.

```java
private final ReentrantLock lock = new ReentrantLock();
private final Condition notFull = lock.newCondition();
private final Condition notEmpty = lock.newCondition();

void put(T item) throws InterruptedException {
    lock.lock();
    try {
        while (items.size() == capacity) {
            notFull.await();            // producer chờ ở hàng "chưa đầy"
        }
        items.add(item);
        notEmpty.signal();              // chỉ đánh thức consumer
    } finally {
        lock.unlock();
    }
}
```

Producer và consumer chờ ở hai hàng khác nhau nên `signal()` luôn đánh thức đúng loại thread, không phải đánh thức tất
cả như `notifyAll`. `ArrayBlockingQueue` của JDK được viết đúng theo cách này. Tên method khác đi để khỏi lẫn với của
`Object`: `await` / `signal` / `signalAll`.

## 7. Bài toán producer–consumer: chọn cách nào

| Cách | Khi nào |
|------|---------|
| `BlockingQueue` có giới hạn | Mặc định. Đúng, ngắn, có backpressure |
| Thread pool | Khi "consumer" chỉ là "chạy task này" |
| `Condition` | Cần điều kiện chờ phức tạp hơn "rỗng/đầy" |
| `wait`/`notify` | Code cũ; học để hiểu |

## 8. Tự kiểm tra

1. Cần chờ 5 dịch vụ khởi động xong rồi mới nhận request. Dùng gì?
2. Vì sao `wait()` phải nằm trong `while` chứ không phải `if`?
3. Quên `release()` một `Semaphore` khi có exception thì triệu chứng về lâu dài là gì?
4. `Thread.sleep(1000)` bên trong khối `synchronized` khác gì `wait(1000)`?

Đáp án ngắn: (1) `CountDownLatch(5)`. (2) Điều kiện có thể không còn đúng khi tỉnh dậy: thread khác đã chen vào, hoặc
bị đánh thức vô cớ. (3) Số giấy phép giảm dần; hệ thống chậm dần rồi treo hẳn dù không có tải. (4) `sleep` giữ nguyên
khoá suốt một giây, chặn mọi thread khác cần khoá đó; `wait` nhả khoá trong lúc chờ.

Bài tiếp theo: [08 — Virtual thread](08-virtual-thread.md).
