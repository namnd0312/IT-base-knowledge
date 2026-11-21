# 02 — Các lỗi memory thường gặp

Dòng thông báo lỗi cho biết **vùng nhớ nào hết**. Xác định đúng vùng là bước đầu tiên,
vì công cụ và cách sửa của mỗi vùng khác nhau hoàn toàn.

## 1. Bảng tra nhanh

| Thông báo | Vùng | Nguyên nhân thường gặp | Việc làm đầu tiên |
|-----------|------|------------------------|-------------------|
| `OutOfMemoryError: Java heap space` | Heap | Leak; heap nhỏ hơn tải; nạp dữ liệu quá lớn một lần | Mở heap dump, xem Dominator Tree |
| `OutOfMemoryError: GC overhead limit exceeded` | Heap | Như trên, nhưng chết từ từ: GC chạy liên tục mà gần như không thu được gì (Parallel GC) | Như `Java heap space` |
| `OutOfMemoryError: Metaspace` | Metaspace | Leak ClassLoader (hot-redeploy, plugin); sinh class động không ngừng (proxy, script engine) | `jcmd <pid> VM.classloader_stats` |
| `OutOfMemoryError: Compressed class space` | Metaspace (phần class) | Như Metaspace | Như Metaspace; xem `-XX:CompressedClassSpaceSize` |
| `OutOfMemoryError: Cannot reserve N bytes of direct buffer memory` (JDK cũ: `Direct buffer memory`) | Native | Giữ `ByteBuffer` direct không trả; pool của Netty/NIO cấu hình quá lớn | `BufferPoolMXBean`, Native Memory Tracking |
| `OutOfMemoryError: unable to create native thread` | Hệ điều hành | Tạo thread không giới hạn; chạm `ulimit -u` hoặc hết RAM cho stack | `jcmd <pid> Thread.print`, đếm thread |
| `OutOfMemoryError: Requested array size exceeds VM limit` | — | Code xin mảng gần `Integer.MAX_VALUE` phần tử, thường do tính sai kích thước | Đọc stack trace, sửa chỗ cấp phát |
| `StackOverflowError` | Stack | Đệ quy vô hạn; đệ quy sâu theo dữ liệu; `toString`/`equals` vòng tròn giữa hai object | Đọc stack trace, tìm đoạn lặp |
| Process biến mất, exit code `137`, không có exception | Container / OS | Tổng RSS (heap + native) vượt memory limit, bị kernel kill | `kubectl describe pod` → `OOMKilled`; so `-Xmx` với limit |

Lưu ý khi đọc thông báo:

- `OutOfMemoryError` là `Error`, không phải `Exception`. Đừng `catch` rồi chạy tiếp: thread khác có thể
  đã chết giữa chừng và để lại trạng thái hỏng. Hãy để process thoát (`-XX:+ExitOnOutOfMemoryError`).
- **Stack trace của OOM chỉ ra nơi xin bộ nhớ cuối cùng, không phải nơi gây leak.** Thủ phạm đã lấp
  heap từ trước; nạn nhân là dòng code vô tình xin thêm vài byte. Phải dùng heap dump mới tìm ra thủ phạm.
- Khi heap cạn hẳn, JVM có thể không in nổi stack trace. Chạy demo 1 bạn sẽ thấy đúng dòng này thay cho
  stack trace: `Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread "main"`.

## 2. Các kiểu leak heap kinh điển

### 2.1 Collection static chỉ thêm không bớt

```java
static final Map<String, UserSession> CACHE = new HashMap<>();   // lớn theo số user
```

Sửa: đặt trần kích thước và chính sách loại bỏ (LRU, TTL). Dùng `LinkedHashMap.removeEldestEntry`
hoặc thư viện cache như Caffeine. → [Demo 1](04-thuc-hanh-demo-tim-va-fix.md#demo-1--heap-leak-do-cache-không-giới-hạn)

### 2.2 `ThreadLocal` trong thread pool không `remove()`

Thread của pool sống suốt đời ứng dụng, nên giá trị `ThreadLocal` cũng vậy.

```java
try {
    CONTEXT.set(value);
    process();
} finally {
    CONTEXT.remove();          // bắt buộc
}
```

→ [Demo 4](04-thuc-hanh-demo-tim-va-fix.md#demo-4--threadlocal-trong-thread-pool)

### 2.3 Listener / callback đăng ký mà không huỷ

```java
eventBus.register(this);       // eventBus sống lâu giữ `this` mãi
```

Sửa: huỷ đăng ký ở vòng đời tương ứng (`close()`, `@PreDestroy`), hoặc để bên phát giữ weak reference.

### 2.4 Tài nguyên không đóng

`InputStream`, `Connection`, `ResultSet`, `Statement`, HTTP client response. Ngoài heap còn giữ cả
file descriptor và bộ nhớ native.

```java
try (Connection c = dataSource.getConnection();
     PreparedStatement ps = c.prepareStatement(sql)) {
    ...
}                              // try-with-resources luôn đóng
```

### 2.5 Key của `HashMap` có `equals`/`hashCode` sai hoặc bị sửa sau khi put

Thiếu `hashCode`/`equals` thì mỗi lần `put` cùng một key logic lại thành một entry mới.
Sửa field tham gia `hashCode` sau khi put thì không bao giờ `remove` được entry đó nữa.
Sửa: key bất biến (`record`, `String`), cài đủ cả `equals` và `hashCode`.

### 2.6 Inner class / lambda giữ ngầm object bao ngoài

Inner class không `static` luôn mang một tham chiếu tới instance bên ngoài. Đưa nó vào chỗ sống lâu
(executor, cache, listener) là kéo theo cả object ngoài. Sửa: dùng `static` nested class, chỉ truyền
đúng dữ liệu cần.

### 2.7 Nạp cả tập dữ liệu vào RAM

`findAll()` trên bảng triệu dòng, `Files.readAllBytes` file vài GB, gom toàn bộ kết quả vào `List`
rồi mới xử lý. Không phải leak nhưng cho cùng lỗi. Sửa: phân trang, stream, xử lý theo lô.

## 3. Leak ngoài heap

| Kiểu | Dấu hiệu | Hướng sửa |
|------|----------|-----------|
| ClassLoader bị giữ (redeploy, plugin, sinh class động) | Số class nạp tăng mãi, Metaspace tăng | Tìm tham chiếu tới ClassLoader cũ (thread chưa dừng, `ThreadLocal`, driver JDBC đăng ký static); cache class thay vì sinh lại |
| Direct buffer | Heap phẳng, RSS tăng | Dùng pool có trần, trả buffer đúng vòng đời |
| Thread | Số thread tăng mãi, mỗi thread giữ ~1MB stack | Dùng pool có giới hạn thay vì `new Thread` theo request; nhớ `shutdown()` executor |
| Thư viện native (JNI, nén, ảnh) | NMT không thấy tăng nhưng RSS vẫn tăng | Kiểm tra `close()`/`end()` của `Inflater`, `Deflater`, v.v.; dùng công cụ mức OS |

## 4. Không phải mọi OOM đều là leak

Trước khi săn leak, trả lời ba câu:

1. **Mức heap sau Full GC có tăng dần theo thời gian không?** Có → leak. Không → heap nhỏ hoặc đột biến.
2. **OOM có trùng với một thao tác cụ thể không** (export báo cáo, import file)? Có → đột biến, xem 2.7.
3. **Có thay đổi gì gần đây?** Tải tăng, bản deploy mới, đổi `-Xmx`, đổi memory limit của container.

Cách đo câu 1 nằm ở [03 — Công cụ và quy trình trace](03-cong-cu-va-quy-trinh-trace.md).
