# 04 — Atomic, concurrent collection và chiến lược thread-safe

Mục tiêu: sau bài này bạn biết các cách đạt thread-safe mà không cần tự viết khoá, dùng đúng `ConcurrentHashMap`
và `BlockingQueue`, và nhận ra lỗi "dùng collection an toàn theo cách không an toàn".

## 1. Bốn chiến lược, theo thứ tự ưu tiên

Lỗi đa luồng cần ba yếu tố cùng lúc: dữ liệu **dùng chung**, **thay đổi được**, và có thread **ghi**. Mỗi chiến lược
loại bỏ một yếu tố.

| # | Chiến lược | Loại bỏ | Ví dụ |
|---|-----------|---------|-------|
| 1 | Không chia sẻ (thread confinement) | Dùng chung | Biến cục bộ, object tạo mới cho từng request, `ThreadLocal` |
| 2 | Bất biến (immutability) | Thay đổi được | `record`, `String`, `List.of(...)`, field `final` |
| 3 | Dùng cấu trúc thread-safe có sẵn | Tự quản lý đồng bộ | `AtomicInteger`, `ConcurrentHashMap`, `BlockingQueue` |
| 4 | Khoá | (không loại bỏ, chỉ kiểm soát) | `synchronized`, `Lock` (bài 3) |

Càng ở trên càng ít chỗ để sai. Phần lớn code dịch vụ web nên dừng ở 1 và 2: controller, service không có field
thay đổi được; dữ liệu của mỗi request nằm trong biến cục bộ.

## 2. Không chia sẻ

Object chỉ một thread chạm tới thì không cần đồng bộ gì.

```java
void handle(Request request) {
    List<Item> items = new ArrayList<>();     // cục bộ: chỉ thread này thấy
    ...
}
```

Cẩn thận với **rò rỉ tham chiếu**: object cục bộ được nhét vào field static, vào cache dùng chung, hoặc truyền cho
thread khác thì không còn "riêng" nữa.

Lỗi kinh điển trong Spring: bean singleton có field thay đổi được.

```java
@Service
class ReportService {
    private List<Row> rows = new ArrayList<>();   // SAI: mọi request dùng chung một list
    private SimpleDateFormat format = ...;        // SAI: SimpleDateFormat không thread-safe
}
```

### `ThreadLocal`

Mỗi thread có một bản riêng của biến.

```java
static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

CURRENT_USER.set("an");
try {
    System.out.println(CURRENT_USER.get());
} finally {
    CURRENT_USER.remove();      // bắt buộc khi thread thuộc pool
}
```

Thread trong pool sống rất lâu và được dùng lại. Không `remove()` thì giá trị của request trước dính sang request sau
(lộ dữ liệu giữa các user), và object bị giữ lại mãi (rò bộ nhớ; xem demo `ThreadLocalLeakInThreadPoolDemo` trong
[Java/memory](../../memory/)). Giá trị `ThreadLocal` cũng không tự theo sang thread khác khi bạn chuyển việc cho pool.

## 3. Bất biến

Object không đổi được sau khi tạo thì bao nhiêu thread đọc cũng an toàn, không cần khoá, không cần `volatile`
cho nội dung của nó.

```java
record Money(long amount, String currency) {
    Money plus(Money other) { return new Money(amount + other.amount, currency); }   // trả object mới
}
```

Điều kiện để thật sự bất biến:

- Mọi field `final`, không có setter.
- Field kiểu collection phải là bản sao không sửa được: `List.copyOf(input)`. `final List` chỉ khoá tham chiếu,
  không khoá nội dung.
- Không để lộ `this` trong constructor (đăng ký listener, khởi động thread).

Mẫu rất hữu dụng: **trạng thái bất biến + một tham chiếu `volatile`**. Muốn đổi cấu hình thì tạo object mới rồi gán
đè; thread đọc luôn thấy một bản nhất quán, cũ hoặc mới, không bao giờ nửa nọ nửa kia.

```java
private volatile Config config = Config.load();

void reload() { config = Config.load(); }      // thay cả cụm
```

## 4. Atomic và CAS

```java
AtomicInteger counter = new AtomicInteger();
counter.incrementAndGet();                              // ++ nguyên tử
counter.updateAndGet(v -> Math.min(v * 2, 100));        // đọc-tính-ghi nguyên tử
boolean swapped = counter.compareAndSet(2, 10);         // chỉ ghi 10 nếu đang là 2

LongAdder requests = new LongAdder();                   // bộ đếm ghi rất nhiều, đọc ít
requests.increment();
long total = requests.sum();

AtomicReference<List<String>> config = new AtomicReference<>(List.of("a"));
config.set(List.of("a", "b"));
```

Bên dưới là **CAS (compare-and-swap)**, một lệnh của CPU: "nếu ô nhớ đang chứa giá trị tôi vừa đọc thì ghi giá trị mới,
không thì báo thất bại". `incrementAndGet` là vòng lặp: đọc, tính, CAS; thất bại (thread khác đã chen vào) thì làm lại.
Không thread nào bị chặn, nên không có deadlock.

| Lớp | Dùng khi |
|-----|----------|
| `AtomicInteger`, `AtomicLong` | Bộ đếm, sinh số thứ tự |
| `LongAdder` | Bộ đếm bị rất nhiều thread ghi (metrics). Nhanh hơn `AtomicLong` khi tranh chấp cao, `sum()` không phải ảnh chụp chính xác tuyệt đối |
| `AtomicBoolean` | Cờ "chỉ làm một lần": `if (started.compareAndSet(false, true))` |
| `AtomicReference<T>` | Thay cả một object bất biến một cách nguyên tử |

Giới hạn: atomic chỉ bảo đảm cho **một biến**. Hai `AtomicInteger` phải nhất quán với nhau (ví dụ `min <= max`) thì
vẫn cần khoá, hoặc gộp cả hai vào một object bất biến đặt trong `AtomicReference`.

Lưu ý: hàm truyền vào `updateAndGet` có thể bị gọi lại nhiều lần khi CAS thất bại, nên không được có tác dụng phụ.

## 5. Concurrent collection

| Cần | Dùng | Không dùng |
|-----|------|-----------|
| Map dùng chung | `ConcurrentHashMap` | `HashMap` (có thể hỏng cấu trúc, treo), `Hashtable`, `Collections.synchronizedMap` (một khoá cho cả map) |
| List đọc rất nhiều, ghi hiếm (listener, cấu hình) | `CopyOnWriteArrayList` | `ArrayList` |
| Hàng đợi giữa các thread | `BlockingQueue` | `LinkedList` |
| Map có thứ tự | `ConcurrentSkipListMap` | `TreeMap` |
| Set dùng chung | `ConcurrentHashMap.newKeySet()` | `HashSet` |

`HashMap` dùng từ nhiều thread không chỉ "mất vài phần tử": cấu trúc bên trong có thể hỏng, `get` trả `null` cho key
đang có, hoặc ném `ConcurrentModificationException` khi duyệt.

### Cạm bẫy: thao tác ghép trên collection an toàn

Demo: [CheckThenActConcurrentMapDemo.java](../demos/CheckThenActConcurrentMapDemo.java). 8 thread đếm lượt truy cập trong một `ConcurrentHashMap`.

```java
static void recordHitBroken() {
    Integer current = hits.get(PAGE);                    // an toàn
    hits.put(PAGE, current == null ? 1 : current + 1);   // an toàn
}                                                        // ghép lại: KHÔNG an toàn
```

```
mode=broken threads=8 hitsPerThread=200,000
expected=1,600,000 actual=231,637 lost=1,368,363
RESULT: WRONG (lost updates)
```

Từng lời gọi `get`, `put` là thread-safe; map không hỏng. Nhưng giữa `get` và `put`, thread khác đã kịp ghi, và lần
`put` này đè mất. Đây là đúng lỗi read-modify-write của bài 2, chỉ là ẩn sau một class có chữ "Concurrent".

**"Thread-safe" nghĩa là mỗi method an toàn khi gọi riêng lẻ. Chuỗi nhiều lời gọi thì không.**

Sửa bằng method ghép nguyên tử mà `ConcurrentHashMap` cung cấp:

```java
static void recordHitFixed() {
    hits.merge(PAGE, 1, Integer::sum);
}
```

```
expected=1,600,000 actual=1,600,000 lost=0
RESULT: CORRECT
```

| Thay vì | Dùng |
|---------|------|
| `if (!map.containsKey(k)) map.put(k, v)` | `map.putIfAbsent(k, v)` |
| `v = map.get(k); if (v == null) { v = create(); map.put(k, v); }` | `map.computeIfAbsent(k, key -> create())` |
| `map.put(k, map.get(k) + 1)` | `map.merge(k, 1, Integer::sum)` |
| `if (map.get(k) == old) map.put(k, neu)` | `map.replace(k, old, neu)` |

```java
ConcurrentHashMap<String, List<String>> cache = new ConcurrentHashMap<>();
cache.computeIfAbsent("k", key -> new CopyOnWriteArrayList<>()).add("v");
```

Hàm truyền vào `compute*`/`merge` chạy khi đang giữ khoá của ô đó: giữ nó ngắn, không gọi I/O, và **không sửa chính
map đó** bên trong (có thể treo). Giá trị lấy ra từ map cũng phải tự thread-safe: `ConcurrentHashMap<String, ArrayList>`
chỉ bảo vệ map, không bảo vệ từng `ArrayList`.

Các method tổng hợp như `size()`, `isEmpty()` trên `ConcurrentHashMap` chỉ là ước lượng tại một thời điểm; đừng dựa
vào chúng để ra quyết định.

## 6. `BlockingQueue`: producer–consumer

Hàng đợi chặn là cách chuẩn để chuyển việc giữa các thread. Nó làm sẵn ba thứ khó: an toàn, chờ khi rỗng, chờ khi đầy.

```java
BlockingQueue<String> queue = new ArrayBlockingQueue<>(100);     // có giới hạn

Thread consumer = new Thread(() -> {
    try {
        while (true) {
            String job = queue.take();          // chặn khi rỗng
            if (job.equals("STOP")) { break; }
            System.out.println("handled " + job);
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
});
consumer.start();

queue.put("job-1");                                              // chặn khi đầy
boolean accepted = queue.offer("job-2", 1, TimeUnit.SECONDS);    // chờ tối đa 1 giây
queue.put("STOP");
```

| Method | Khi đầy / rỗng |
|--------|----------------|
| `put` / `take` | Chặn cho đến khi được |
| `offer(e, timeout)` / `poll(timeout)` | Chờ có hạn, trả `false` / `null` |
| `offer(e)` / `poll()` | Trả ngay `false` / `null` |
| `add` / `remove` | Ném exception |

**Luôn đặt giới hạn cho hàng đợi.** Hàng đợi không giới hạn (`new LinkedBlockingQueue<>()`) che giấu việc consumer
chậm hơn producer cho đến khi hết bộ nhớ. Hàng đợi có giới hạn tạo ra **backpressure**: producer bị chậm lại khi
hệ thống quá tải, và đó là hành vi đúng.

## 7. Chọn công cụ

| Tình huống | Công cụ |
|-----------|---------|
| Bộ đếm, số thứ tự | `AtomicLong` / `LongAdder` |
| Cờ bật tắt, cờ dừng | `volatile boolean` |
| Cấu hình thay cả cụm | Object bất biến + `volatile` hoặc `AtomicReference` |
| Cache, bảng tra dùng chung | `ConcurrentHashMap` + `computeIfAbsent` |
| Chuyển việc giữa các thread | `BlockingQueue` có giới hạn, hoặc thread pool |
| Nhiều biến phải nhất quán với nhau | Khoá (bài 3) |
| Dữ liệu theo từng request | Biến cục bộ; `ThreadLocal` nếu buộc phải, luôn `remove()` |

## 8. Tự kiểm tra

1. Service singleton có field `private final Map<String, User> cache = new HashMap<>()`. `final` có làm nó an toàn không?
2. `ConcurrentHashMap<String, Integer>`: vì sao `map.put(k, map.get(k) + 1)` vẫn sai?
3. Vì sao hàm truyền cho `AtomicInteger.updateAndGet` không được có tác dụng phụ?
4. Hàng đợi không giới hạn giữa producer nhanh và consumer chậm dẫn tới điều gì?

Đáp án ngắn: (1) Không: `final` chỉ cố định tham chiếu, nội dung `HashMap` vẫn bị nhiều thread sửa. (2) `get` và `put`
là hai lời gọi riêng, thread khác chen vào giữa được. (3) Nó có thể bị gọi lại nhiều lần khi CAS thất bại.
(4) Hàng đợi phình mãi rồi `OutOfMemoryError`; hệ thống trông bình thường cho tới lúc sập.

Bài tiếp theo: [05 — Thread pool và Executor](05-thread-pool-va-executor.md).
