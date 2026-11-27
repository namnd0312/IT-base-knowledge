# 03 — synchronized, Lock và deadlock

Mục tiêu: sau bài này bạn bảo vệ được trạng thái dùng chung bằng khoá, chọn được giữa `synchronized` và
`ReentrantLock`, và biết vì sao deadlock xảy ra cùng cách thiết kế để nó không thể xảy ra.

## 1. Ý tưởng của khoá

Khoá biến một đoạn code thành **vùng loại trừ lẫn nhau** (critical section): tại một thời điểm chỉ một thread được ở
trong đó. Các bước đọc, tính, ghi không còn bị chen ngang, và nhờ quy tắc happens-before (bài 2), thread vào sau
chắc chắn thấy dữ liệu thread trước đã ghi.

Câu hỏi phải trả lời được cho mọi biến dùng chung: **biến này được bảo vệ bởi khoá nào?** Nếu không trả lời được,
hoặc câu trả lời khác nhau ở các chỗ khác nhau, code đó sai.

## 2. `synchronized`

Mọi object Java đều có sẵn một khoá (intrinsic lock, còn gọi là monitor).

```java
static class SyncCounter {
    private int value;
    public synchronized void increment() { value++; }   // khoá trên this
    public synchronized int get() { return value; }     // đọc cũng phải khoá
}
```

| Cách viết | Khoá trên |
|-----------|-----------|
| `synchronized void m()` | `this` |
| `static synchronized void m()` | Object `Class` (ví dụ `Counter.class`) |
| `synchronized (obj) { ... }` | `obj` |

Tính chất:

- **Tự nhả**: ra khỏi khối, kể cả do exception, là khoá được nhả. Không thể quên.
- **Reentrant** (vào lại được): thread đang giữ khoá gọi tiếp một method `synchronized` khác trên cùng object thì
  không tự chặn chính nó.
- **Không ngắt được, không có timeout**: thread chờ khoá sẽ chờ mãi.

### Lỗi thường gặp với `synchronized`

| Lỗi | Vì sao sai |
|-----|-----------|
| Khoá ghi nhưng không khoá đọc | Thread đọc không có happens-before, có thể thấy giá trị cũ |
| Hai method dùng hai khoá khác nhau cho cùng một dữ liệu | Không loại trừ lẫn nhau |
| `synchronized (new Object())` hoặc khoá trên biến cục bộ | Mỗi thread một khoá riêng: không bảo vệ gì |
| Khoá trên `String` literal, `Integer`, `Boolean` | Các object này được dùng chung toàn JVM; code không liên quan có thể tranh cùng khoá |
| Khoá trên field có thể bị gán lại | Sau khi gán, thread khác khoá trên object khác |
| Khoá `this` trong class công khai | Code bên ngoài cũng `synchronized (yourObject)` được và làm kẹt bạn |

Thực hành tốt: dùng một object khoá riêng, `private final`:

```java
private final Object lock = new Object();

void update() {
    synchronized (lock) { ... }
}
```

### Giữ khoá càng ngắn càng tốt

Trong khối khoá chỉ làm việc với trạng thái dùng chung. **Không** gọi I/O (database, HTTP, ghi file), không gọi
code lạ (callback, listener) khi đang giữ khoá: mọi thread khác cần khoá đó sẽ xếp hàng chờ suốt thời gian I/O,
và code lạ có thể lấy thêm khoá khác, mở đường cho deadlock.

## 3. `ReentrantLock`

Cùng ngữ nghĩa với `synchronized`, thêm các khả năng mà `synchronized` không có. Đổi lại, bạn phải tự nhả khoá.

```java
static class LockCounter {
    private final ReentrantLock lock = new ReentrantLock();
    private int value;

    void increment() {
        lock.lock();
        try {
            value++;
        } finally {
            lock.unlock();          // BẮT BUỘC trong finally
        }
    }

    boolean tryIncrement() throws InterruptedException {
        if (!lock.tryLock(200, TimeUnit.MILLISECONDS)) {
            return false;           // không lấy được khoá trong 200 ms: bỏ cuộc thay vì treo
        }
        try {
            value++;
            return true;
        } finally {
            lock.unlock();
        }
    }
}
```

| | `synchronized` | `ReentrantLock` |
|---|---|---|
| Nhả khoá | Tự động | Thủ công, trong `finally` |
| Thử lấy, có timeout | Không | `tryLock()`, `tryLock(timeout)` |
| Chờ khoá có ngắt được | Không | `lockInterruptibly()` |
| Công bằng (ai chờ lâu được trước) | Không | `new ReentrantLock(true)`, chậm hơn |
| Nhiều điều kiện chờ | Một (`wait`/`notify`) | Nhiều `Condition` |
| Chặn bên trong có pin virtual thread (JDK 21–23) | Có | Không |

Chọn thế nào: mặc định `synchronized` vì đơn giản và không quên nhả được. Chuyển sang `ReentrantLock` khi cần
timeout, ngắt được, nhiều `Condition`, hoặc khi đoạn khoá có thao tác chặn chạy trên virtual thread (bài 8).

Quên `unlock()` (hoặc đặt ngoài `finally`) nghĩa là một exception sẽ để khoá bị giữ vĩnh viễn; mọi thread sau đó treo.

## 4. `ReadWriteLock`

Khi đọc nhiều, ghi ít: nhiều thread được **đọc cùng lúc**, nhưng ghi thì độc quyền.

```java
static class PriceCache {
    private final ReadWriteLock rw = new ReentrantReadWriteLock();
    private final Map<String, Long> prices = new HashMap<>();

    Long get(String sku) {
        rw.readLock().lock();
        try { return prices.get(sku); } finally { rw.readLock().unlock(); }
    }

    void put(String sku, long price) {
        rw.writeLock().lock();
        try { prices.put(sku, price); } finally { rw.writeLock().unlock(); }
    }
}
```

Chỉ có lợi khi đoạn đọc đủ dài và tỉ lệ đọc áp đảo; bản thân khoá đọc–ghi nặng hơn khoá thường. Với một map đơn giản
như trên, `ConcurrentHashMap` (bài 4) thường là lựa chọn tốt hơn. Không nâng cấp được từ khoá đọc lên khoá ghi:
thread đang giữ khoá đọc mà xin khoá ghi sẽ tự treo.

## 5. Deadlock

Demo: [DeadlockTransferDemo.java](../demos/DeadlockTransferDemo.java). Hai thread chuyển tiền ngược chiều nhau giữa hai tài khoản.

```java
static void transferBroken(Account from, Account to, long amount) throws InterruptedException {
    synchronized (from) {
        Thread.sleep(100);
        synchronized (to) {
            from.balance -= amount;
            to.balance += amount;
        }
    }
}
```

```
Thread 1: transfer(A, B)              Thread 2: transfer(B, A)
   khoá A  ✔                             khoá B  ✔
   chờ khoá B  ✖ (thread 2 giữ)          chờ khoá A  ✖ (thread 1 giữ)
              └──────────── chờ nhau mãi mãi ────────────┘
```

```
PID=52110 mode=broken
DEADLOCK detected by ThreadMXBean:
  "transfer-A-to-B" BLOCKED, waiting for lock held by "transfer-B-to-A"
  "transfer-B-to-A" BLOCKED, waiting for lock held by "transfer-A-to-B"
RESULT: WRONG (deadlock, transfers never finish)
```

Không exception, không log, CPU bằng 0. Ứng dụng chỉ đơn giản là đứng yên. Trong production, triệu chứng là request
treo dần cho đến khi cạn thread pool.

### Bốn điều kiện

Deadlock chỉ xảy ra khi **cả bốn** điều kiện cùng đúng. Phá một cái là đủ.

| Điều kiện | Nghĩa | Cách phá |
|-----------|-------|----------|
| Loại trừ lẫn nhau | Tài nguyên chỉ một thread giữ được | Dùng cấu trúc không khoá, dữ liệu bất biến |
| Giữ và chờ | Đang giữ khoá này, xin thêm khoá khác | Chỉ giữ một khoá tại một thời điểm; lấy mọi khoá cần trong một lần |
| Không thu hồi được | Không ai lấy lại khoá từ thread đang giữ | `tryLock` có timeout: không lấy được thì nhả hết, lùi lại, thử lại |
| Chờ vòng tròn | A chờ B, B chờ A | **Thứ tự khoá toàn cục** |

### Sửa bằng thứ tự khoá

Cách hiệu quả và phổ biến nhất: mọi nơi trong hệ thống lấy khoá theo cùng một thứ tự.

```java
static void transferFixed(Account from, Account to, long amount) throws InterruptedException {
    Account first = from.id < to.id ? from : to;
    Account second = from.id < to.id ? to : from;
    synchronized (first) {
        Thread.sleep(100);
        synchronized (second) {
            from.balance -= amount;
            to.balance += amount;
        }
    }
}
```

Cả hai thread giờ đều khoá tài khoản 1 trước, tài khoản 2 sau. Thread đến sau chờ ở khoá đầu tiên, khi đó nó chưa
giữ gì nên không thể tạo vòng tròn.

```
PID=52113 mode=fixed
balances: A=1100 B=900 total=2000
RESULT: CORRECT (both transfers finished)
```

### Các nguồn deadlock ít lộ hơn

- **Gọi code lạ khi đang giữ khoá**: listener, callback, method có thể bị override. Bạn không biết nó lấy khoá gì.
- **Hai class gọi chéo nhau**, mỗi bên `synchronized` trên chính nó.
- **Khoá của database** kết hợp khoá trong code: hai transaction cập nhật hai dòng theo thứ tự ngược nhau.
- **Thread pool tự chờ chính nó**: không có khoá nào, nên công cụ phát hiện deadlock không báo (bài 5).

## 6. Livelock và starvation

| | Deadlock | Livelock | Starvation |
|---|---|---|---|
| Thread đang làm gì | Đứng yên chờ | Chạy liên tục nhưng không tiến triển | Một thread không bao giờ đến lượt |
| CPU | 0% | Cao | Tuỳ |
| Ví dụ | Hai thread chờ khoá của nhau | Hai thread cùng `tryLock` thất bại, cùng lùi, cùng thử lại đúng nhịp | Thread ưu tiên thấp, hoặc khoá không công bằng dưới tải cao |
| Hướng sửa | Thứ tự khoá | Thêm khoảng lùi ngẫu nhiên trước khi thử lại | Khoá công bằng, giảm thời gian giữ khoá |

## 7. Chi phí của khoá

Khoá đúng nhưng không miễn phí:

- **Tranh chấp**: thread chờ khoá là thread không làm việc. Khoá càng thô (bao nhiều dữ liệu) và giữ càng lâu,
  hệ thống càng giống chạy một luồng.
- **Chuyển ngữ cảnh**: thread bị chặn rồi được đánh thức tốn thời gian của hệ điều hành.

Thứ tự ưu tiên khi thiết kế: không chia sẻ → chia sẻ dữ liệu bất biến → cấu trúc concurrent có sẵn → khoá.
Ba lựa chọn đầu là nội dung bài 4.

## 8. Tự kiểm tra

1. Method `increment()` có `synchronized`, method `get()` thì không. Có vấn đề gì?
2. Vì sao `unlock()` phải nằm trong `finally`?
3. Hai thread, hai khoá. Viết lại điều kiện nào trong bốn điều kiện bị phá khi áp dụng thứ tự khoá.
4. Ứng dụng treo, CPU 0%. Và ứng dụng treo, CPU 100%. Mỗi trường hợp nghi ngờ điều gì trước?

Đáp án ngắn: (1) `get()` có thể trả giá trị cũ vì không có happens-before với lần ghi. (2) Nếu code giữa `lock()` và
`unlock()` ném exception thì khoá bị giữ mãi. (3) Chờ vòng tròn. (4) CPU 0%: deadlock hoặc mọi thread đang chờ
(bao gồm pool tự kẹt). CPU 100%: vòng lặp không dừng (lỗi visibility) hoặc livelock.

Bài tiếp theo: [04 — Atomic, concurrent collection và chiến lược thread-safe](04-atomic-concurrent-collection-va-chien-luoc-thread-safe.md).
