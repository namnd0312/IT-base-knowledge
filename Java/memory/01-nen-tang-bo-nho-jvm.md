# 01 — Nền tảng bộ nhớ JVM

Mục tiêu: sau bài này bạn trả lời được "object này nằm ở đâu, ai giữ nó, khi nào nó bị dọn".
Đó là toàn bộ kiến thức cần để hiểu mọi lỗi memory ở các bài sau.

## 1. Bức tranh tổng thể

Một process Java không chỉ có heap. Bộ nhớ nó xin của hệ điều hành gồm nhiều vùng:

```
┌──────────────────────── Process Java (RSS) ────────────────────────┐
│                                                                    │
│  HEAP (-Xms / -Xmx)                 ← object do `new` tạo ra       │
│  ┌──────────── Young ───────────┐  ┌────────── Old ────────────┐   │
│  │ Eden │ Survivor 0 │ Survivor 1│  │ object sống lâu           │   │
│  └──────────────────────────────┘  └───────────────────────────┘   │
│                                                                    │
│  NGOÀI HEAP (native memory)                                        │
│  ┌───────────┐ ┌────────────┐ ┌────────────┐ ┌─────────────────┐   │
│  │ Metaspace │ │ Thread     │ │ Code cache │ │ Direct buffer   │   │
│  │ (class)   │ │ stacks     │ │ (JIT)      │ │ (NIO, Netty...) │   │
│  └───────────┘ └────────────┘ └────────────┘ └─────────────────┘   │
│  + cấu trúc nội bộ của GC, symbol, thư viện native (JNI)...        │
└────────────────────────────────────────────────────────────────────┘
```

| Vùng | Chứa gì | Giới hạn bằng | Hết thì báo |
|------|---------|---------------|-------------|
| Heap | Mọi object và mảng | `-Xmx` | `OutOfMemoryError: Java heap space` |
| Stack (mỗi thread một cái) | Stack frame: biến cục bộ, tham số, địa chỉ trả về | `-Xss` | `StackOverflowError` |
| Metaspace | Metadata của class (bytecode, method, constant pool) | `-XX:MaxMetaspaceSize` (mặc định không giới hạn) | `OutOfMemoryError: Metaspace` |
| Direct buffer | Vùng nhớ native của `ByteBuffer.allocateDirect` | `-XX:MaxDirectMemorySize` (mặc định xấp xỉ `-Xmx`) | `OutOfMemoryError: ... direct buffer memory` |
| Code cache | Mã máy do JIT biên dịch | `-XX:ReservedCodeCacheSize` | Cảnh báo `CodeCache is full`, chạy chậm đi |

Hệ quả quan trọng: **RSS của process luôn lớn hơn `-Xmx`**. Đặt `-Xmx` bằng đúng RAM của
container là công thức để bị hệ điều hành kill (xem lỗi OOMKilled ở bài 2).

## 2. Stack và heap qua một ví dụ

```java
void handle() {
    int count = 3;                      // count: nằm trên stack
    User user = new User("an");         // biến `user` (tham chiếu): stack
                                        // object User: heap
    List<User> users = new ArrayList<>();
    users.add(user);                    // heap: ArrayList -> User
}                                       // hết hàm: frame bị huỷ, count/user/users biến mất
                                        // object trên heap thành rác nếu không ai khác trỏ tới
```

- **Stack**: tự động, theo lời gọi hàm. Vào hàm thì thêm một frame, ra hàm thì bỏ. Không cần GC.
  Mỗi thread có stack riêng nên biến cục bộ không bị thread khác thấy.
- **Heap**: dùng chung cho mọi thread. Object sống đến khi không còn ai tham chiếu, rồi GC dọn.

## 3. Garbage Collector quyết định "rác" thế nào

GC không đếm tham chiếu. Nó đi từ một tập điểm xuất phát gọi là **GC root**, lần theo mọi tham chiếu.
Cái gì tới được thì còn sống, cái gì không tới được là rác.

Các GC root chính:

| GC root | Ví dụ | Sống bao lâu |
|---------|-------|--------------|
| Biến cục bộ trên stack của thread đang chạy | `user` trong hàm đang thực thi | Đến khi hàm trả về |
| Field `static` | `static Map CACHE` | Đến khi class bị gỡ (thường là mãi mãi) |
| Thread đang sống | Bản thân thread và `ThreadLocal` của nó | Đến khi thread kết thúc |
| Tham chiếu JNI, monitor đang bị khoá | Object native code đang giữ | Tuỳ native code |

Ghi nhớ bảng này: phần lớn leak trong thực tế bắt nguồn từ **field static** và **thread sống lâu**.

### Vì sao heap chia Young/Old

Quan sát thực nghiệm: đa số object chết rất sớm (object tạm trong một request). Nên:

1. Object mới sinh ra ở **Eden**.
2. Eden đầy → **Young GC** (nhanh): object còn sống được chép sang Survivor, phần còn lại bỏ cả.
3. Object sống sót qua nhiều lần Young GC → được đẩy lên **Old**.
4. Old đầy → GC nặng hơn (Mixed/Full GC), dừng ứng dụng lâu hơn.

Mọi lần GC đều có khoảng **stop-the-world** (STW): toàn bộ thread ứng dụng dừng. Young GC dừng
vài mili giây; Full GC có thể dừng hàng giây với heap lớn. Triệu chứng "ứng dụng thỉnh thoảng đơ"
thường là Full GC lặp lại vì Old gần đầy.

### Các bộ GC

| GC | Bật bằng | Dùng khi |
|----|----------|----------|
| G1 | Mặc định từ JDK 9 | Lựa chọn chung cho server |
| Parallel | `-XX:+UseParallelGC` | Batch, ưu tiên throughput, chấp nhận dừng lâu |
| ZGC | `-XX:+UseZGC` | Heap lớn, cần độ trễ rất thấp |
| Serial | `-XX:+UseSerialGC` | Heap nhỏ, 1 CPU |

Đổi GC không chữa được leak. Nó chỉ thay đổi cách ứng dụng chết.

## 4. Bốn loại tham chiếu

| Loại | GC dọn khi nào | Dùng cho |
|------|----------------|----------|
| Strong (mặc định) | Không bao giờ, chừng nào còn tới được | Mọi thứ thông thường |
| `SoftReference` | Khi sắp hết heap | Cache "có thì tốt" |
| `WeakReference` | Ở lần GC kế tiếp nếu chỉ còn weak trỏ tới | `WeakHashMap`, metadata gắn theo object |
| `PhantomReference` | Sau khi object đã chết, để dọn tài nguyên | Thay cho `finalize()` |

Khi phân tích heap dump, bạn luôn loại weak/soft ra khỏi đường tới GC root: chúng không giữ object sống.

## 5. Memory leak trong Java là gì

Java không có leak kiểu C (quên `free`). Leak trong Java là:

> Object **không còn được dùng** nhưng **vẫn tới được từ GC root**, nên GC không được phép dọn.

Ví dụ ngắn nhất:

```java
static final Map<String, UserSession> CACHE = new HashMap<>();   // GC root

void login(String userId) {
    CACHE.put(userId, new UserSession(userId));   // thêm vào
}                                                 // không có chỗ nào remove
```

Phân biệt ba tình huống có cùng triệu chứng OOM:

| Tình huống | Đồ thị heap sau mỗi lần Full GC | Cách xử lý |
|------------|---------------------------------|------------|
| **Leak** | Mức đáy tăng dần, không bao giờ về lại | Tìm ai đang giữ, sửa code |
| **Heap quá nhỏ** so với tải thật | Đáy ổn định nhưng sát trần | Tăng `-Xmx` hoặc giảm dữ liệu giữ trong RAM |
| **Đột biến** (nạp một file/query khổng lồ) | Bình thường, rồi vọt lên một lần | Xử lý theo luồng/phân trang, không nạp hết vào RAM |

Tăng `-Xmx` cho một ca leak chỉ làm nó chết muộn hơn.

## 6. Các tham số cần thuộc

| Tham số | Ý nghĩa | Ghi chú |
|---------|---------|---------|
| `-Xms` / `-Xmx` | Heap khởi đầu / tối đa | Không đặt `-Xmx` thì mặc định 1/4 RAM máy |
| `-Xss` | Kích thước stack mỗi thread | Mặc định khoảng 512KB–1MB tuỳ nền tảng |
| `-XX:MaxMetaspaceSize` | Trần Metaspace | Mặc định không giới hạn — nên đặt trần để leak lộ sớm |
| `-XX:MaxDirectMemorySize` | Trần direct buffer | |
| `-XX:MaxRAMPercentage=75` | Heap tối đa theo % RAM container | Dùng thay `-Xmx` cứng trong Docker/K8s |
| `-XX:+HeapDumpOnOutOfMemoryError` | Tự chụp heap khi OOM | **Luôn bật ở production** |
| `-XX:HeapDumpPath=<dir>` | Nơi ghi heap dump | Thư mục phải tồn tại và đủ dung lượng (cỡ bằng heap) |
| `-XX:+ExitOnOutOfMemoryError` | Thoát ngay khi OOM | Để orchestrator restart thay vì chạy tiếp ở trạng thái hỏng |
| `-Xlog:gc*:file=gc.log` | Ghi GC log | Overhead rất thấp, nên bật sẵn |
| `-XX:NativeMemoryTracking=summary` | Theo dõi bộ nhớ ngoài heap | Overhead vài %, bật khi nghi leak native |

Tiếp theo: [02 — Các lỗi memory thường gặp](02-cac-loi-memory-thuong-gap.md).
