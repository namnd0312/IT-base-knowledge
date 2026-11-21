# 04 — Thực hành: tìm và fix qua 5 demo

Mỗi demo đi theo cùng một vòng: **chạy bản lỗi → quan sát → trace → đọc code tìm nguyên nhân → chạy bản fix → kiểm chứng**.
Output mẫu lấy từ JDK 21.0.5.

## Chuẩn bị

```bash
cd Java/memory
javac -d out demos/*.java
mkdir -p dumps
```

Dùng hai terminal: **T1** chạy demo, **T2** chạy lệnh trace. Mỗi demo in `PID=...` ở dòng đầu;
hoặc lấy PID bằng `jcmd -l`.

Cần thêm thời gian để gõ lệnh ở T2 thì tăng `-DpauseMs` (ví dụ `-DpauseMs=100`).

---

## Demo 1 — Heap leak do cache không giới hạn

File: [HeapLeakUnboundedCacheDemo.java](demos/HeapLeakUnboundedCacheDemo.java)

### Chạy bản lỗi (T1)

```bash
java -Xmx64m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=dumps \
     -Xlog:gc:file=dumps/gc.log -DpauseMs=40 \
     -cp out HeapLeakUnboundedCacheDemo leak
```

```
PID=56633 mode=leak maxHeap=64MB
requests=100 cacheSize=100 heapUsed=12MB
requests=200 cacheSize=200 heapUsed=22MB
...
requests=600 cacheSize=600 heapUsed=61MB
java.lang.OutOfMemoryError: Java heap space
Dumping heap to dumps/java_pid56633.hprof ...
Heap dump file created [67560345 bytes in 0.052 secs]

Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread "main"
```

Quan sát: `heapUsed` tăng đều khoảng 10MB mỗi 100 request, tỉ lệ thuận với `cacheSize`. Không có stack trace
vì heap cạn tới mức JVM không in nổi — lý do phải luôn bật `HeapDumpOnOutOfMemoryError`.

### Trace khi đang chạy (T2)

```bash
jstat -gcutil <pid> 2000
jcmd <pid> GC.class_histogram | grep UserSession
# chờ ~10 giây
jcmd <pid> GC.class_histogram | grep UserSession
```

```
  41:           120           2880  HeapLeakUnboundedCacheDemo$UserSession
  19:           307           7368  HeapLeakUnboundedCacheDemo$UserSession
```

Số `UserSession` chỉ tăng. Sau khi process chết, xem GC log:

```bash
tail dumps/gc.log
```

```
[26.709s][info][gc] GC(31) Pause Full (G1 Compaction Pause) 62M->62M(64M) 3.464ms
[26.714s][info][gc] GC(33) Pause Full (G1 Compaction Pause) 62M->62M(64M) 4.416ms
```

Full GC liên tiếp, không thu hồi được gì.

### Tìm ai giữ

Mở `dumps/java_pid<pid>.hprof` bằng MAT → Dominator Tree → object retained lớn nhất → Path To GC Roots.
Chuỗi cần thấy:

```
class HeapLeakUnboundedCacheDemo
  └─ LEAKY_CACHE (static)  → java.util.HashMap
       └─ table            → HashMap$Node[]
            └─ value       → UserSession
                 └─ profileData → byte[102400]
```

### Nguyên nhân và cách fix

```java
// LỖI: static (GC root) + chỉ put, không remove
static final Map<String, UserSession> LEAKY_CACHE = new HashMap<>();

// FIX: LRU có trần 100 phần tử
static final Map<String, UserSession> BOUNDED_CACHE =
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, UserSession> eldest) {
                return size() > MAX_CACHED_SESSIONS;
            }
        };
```

Trong dự án thật: dùng Caffeine/Guava cache với `maximumSize` và `expireAfterAccess`, và bọc đồng bộ
nếu nhiều thread truy cập (`LinkedHashMap` không thread-safe).

### Kiểm chứng

```bash
java -Xmx64m -cp out HeapLeakUnboundedCacheDemo fixed
```

```
requests=1300 cacheSize=100 heapUsed=24MB
requests=1400 cacheSize=100 heapUsed=29MB
requests=1500 cacheSize=100 heapUsed=38MB
DONE - heap on dinh, khong OOM
```

1500 request, gấp 2,5 lần mức bản lỗi chết, `cacheSize` đứng ở 100. `heapUsed` vẫn lên xuống
(rác chờ GC) nhưng không leo thang — đó là hình răng cưa bình thường.

---

## Demo 2 — StackOverflowError

File: [StackOverflowDeepRecursionDemo.java](demos/StackOverflowDeepRecursionDemo.java)

### Chạy bản lỗi

```bash
java -cp out StackOverflowDeepRecursionDemo leak 2>&1 | head -5
```

```
PID=56644 mode=leak nodes=1000000
Exception in thread "main" java.lang.StackOverflowError
	at StackOverflowDeepRecursionDemo.lengthRecursive(StackOverflowDeepRecursionDemo.java:26)
	at StackOverflowDeepRecursionDemo.lengthRecursive(StackOverflowDeepRecursionDemo.java:26)
	at StackOverflowDeepRecursionDemo.lengthRecursive(StackOverflowDeepRecursionDemo.java:26)
```

### Trace

Lỗi này không cần công cụ: stack trace đã chỉ thẳng. Cách đọc:

- Tìm **đoạn lặp**. Một dòng lặp lại = hàm tự gọi nó. Một cụm vài dòng lặp lại (`A → B → A → B`) = đệ quy vòng,
  hay gặp ở `toString()`/`hashCode()` của hai entity trỏ lẫn nhau (quan hệ hai chiều trong JPA, Lombok `@Data`).
- JVM chỉ in 1024 frame trên cùng. Không thấy điểm bắt đầu thì chạy lại với `-XX:MaxJavaStackTraceDepth=0`.

Thử nghiệm để hiểu bản chất:

```bash
java -Xmx4g  -cp out StackOverflowDeepRecursionDemo leak    # vẫn lỗi: heap không liên quan
java -Xss512m -cp out StackOverflowDeepRecursionDemo leak   # chạy được: DONE - length=1000000
```

`-Xss` "chữa" được ở đây nhưng chỉ dời giới hạn: dữ liệu lớn hơn nữa lại lỗi, và mọi thread đều tốn thêm stack.

### Nguyên nhân và cách fix

```java
// LỖI: độ sâu đệ quy = số phần tử dữ liệu
static int lengthRecursive(Node node) {
    if (node == null) return 0;
    return 1 + lengthRecursive(node.next);
}

// FIX: vòng lặp, luôn dùng 1 frame
static int lengthIterative(Node node) {
    int length = 0;
    for (Node current = node; current != null; current = current.next) length++;
    return length;
}
```

Quy tắc: đệ quy chỉ an toàn khi độ sâu bị chặn bởi thứ nhỏ (chiều cao cây cân bằng), không phải bởi kích thước
dữ liệu đầu vào. Với đệ quy vô hạn thì thêm/sửa điều kiện dừng; với `toString` vòng thì loại field quan hệ ngược.

### Kiểm chứng

```bash
java -cp out StackOverflowDeepRecursionDemo fixed      # DONE - length=1000000
```

---

## Demo 3 — Metaspace leak do ClassLoader

File: [MetaspaceClassLoaderLeakDemo.java](demos/MetaspaceClassLoaderLeakDemo.java)

### Chạy bản lỗi (T1)

```bash
java -XX:MaxMetaspaceSize=32m -DpauseMs=4 -cp out MetaspaceClassLoaderLeakDemo leak
```

```
calls=1000 loadedClasses=1954 metaspaceCommitted=7MB
calls=2000 loadedClasses=3008 metaspaceCommitted=13MB
calls=3000 loadedClasses=4008 metaspaceCommitted=18MB
calls=4000 loadedClasses=5008 metaspaceCommitted=24MB
calls=5000 loadedClasses=6008 metaspaceCommitted=30MB
Exception in thread "main" java.lang.OutOfMemoryError: Metaspace
	at java.base/java.lang.ClassLoader.defineClass1(Native Method)
	...
	at MetaspaceClassLoaderLeakDemo$PluginClassLoader.definePlugin(MetaspaceClassLoaderLeakDemo.java:39)
	at MetaspaceClassLoaderLeakDemo.loadPluginLeaky(MetaspaceClassLoaderLeakDemo.java:50)
```

Quan sát: `loadedClasses` tăng đúng 1000 mỗi 1000 lần gọi. Ứng dụng bình thường nạp class lúc khởi động rồi đi ngang.
Khác với lỗi heap, stack trace ở đây hữu ích: nó chỉ ra chỗ đang định nghĩa class.

### Trace khi đang chạy (T2)

```bash
jcmd <pid> VM.classloader_stats | tail -5
jcmd <pid> GC.class_histogram | grep ClassLoader
```

```
0x000000e001000bf8  0x000000e00006ad70  0x0000600002011540       1      6144      1368  MetaspaceClassLoaderLeakDemo$PluginClassLoader
Total = 884                                                   1851   9944064   2009888

  10:           902          72160  MetaspaceClassLoaderLeakDemo$PluginClassLoader
```

Gần 900 instance `PluginClassLoader` đang sống, mỗi cái giữ một bản sao class `Plugin`.

### Nguyên nhân và cách fix

Class chỉ bị gỡ khỏi Metaspace khi **ClassLoader của nó** thành rác. ClassLoader thành rác khi không còn
tham chiếu nào tới: bản thân loader, bất kỳ `Class` nào nó nạp, hay bất kỳ instance nào của các class đó.

```java
// LỖI: mỗi lần gọi tạo loader mới, và registry static giữ Class cũ → loader cũ không bao giờ chết
static Class<?> loadPluginLeaky(byte[] classBytes) {
    Class<?> pluginClass = new PluginClassLoader().definePlugin(classBytes);
    LEAKY_PLUGIN_REGISTRY.add(pluginClass);
    return pluginClass;
}

// FIX: mỗi plugin nạp một lần, dùng lại
static Class<?> loadPluginFixed(String pluginName, byte[] classBytes) {
    return PLUGIN_CACHE.computeIfAbsent(pluginName,
            name -> new PluginClassLoader().definePlugin(classBytes));
}
```

Dạng lỗi này ngoài đời: redeploy WAR nhiều lần trên Tomcat mà thread/`ThreadLocal`/JDBC driver của bản cũ còn sống;
tạo `ScriptEngine`, `JAXBContext`, `ObjectMapper` có module sinh class, hay proxy CGLIB mới cho mỗi request.
Cách sửa chung: tạo một lần, dùng lại; khi thật sự cần nạp lại thì cắt hết tham chiếu tới loader cũ.

### Kiểm chứng

```bash
java -XX:MaxMetaspaceSize=32m -cp out MetaspaceClassLoaderLeakDemo fixed
```

```
calls=19000 loadedClasses=1012 metaspaceCommitted=1MB
calls=20000 loadedClasses=1012 metaspaceCommitted=1MB
DONE - metaspace on dinh, khong OOM
```

20.000 lần gọi (bản lỗi chết ở khoảng 5.000), `loadedClasses` đứng yên.

---

## Demo 4 — ThreadLocal trong thread pool

File: [ThreadLocalLeakInThreadPoolDemo.java](demos/ThreadLocalLeakInThreadPoolDemo.java)

### Chạy bản lỗi (T1)

```bash
java -Xmx64m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=dumps \
     -DpauseMs=40 -cp out ThreadLocalLeakInThreadPoolDemo leak
```

```
requests=100 heapUsed=12MB
requests=200 heapUsed=22MB
...
requests=600 heapUsed=61MB
java.lang.OutOfMemoryError: Java heap space
Dumping heap to dumps/java_pid56680.hprof ...
```

### Trace (T2)

```bash
jcmd <pid> GC.class_histogram | head -6
```

```
   1:         17400       31157800  [B (java.base@21.0.5)
   2:            85         671392  [Ljdk.internal.vm.FillerElement; (java.base@21.0.5)
   3:         16091         386184  java.lang.String (java.base@21.0.5)
```

Lần này histogram **không** chỉ ra thủ phạm: chỉ thấy `byte[]` phình, không có class nghiệp vụ nào tăng.
Đây là lúc bắt buộc dùng heap dump. Trong MAT, Dominator Tree sẽ có 4 object `java.lang.Thread`
(`pool-1-thread-1..4`) đứng đầu về retained heap. Path To GC Roots của một `byte[]`:

```
Thread "pool-1-thread-1"                      ← GC root (thread đang sống)
  └─ threadLocals → ThreadLocal$ThreadLocalMap
       └─ table   → ThreadLocalMap$Entry[]
            └─ value → java.util.ArrayList
                 └─ elementData → Object[] → byte[102400]
```

Thấy `threadLocals` trên đường đi là nhận ra ngay kiểu leak này.

### Nguyên nhân và cách fix

```java
// LỖI: thread của pool không chết → giá trị ThreadLocal sống qua mọi request
static void handleRequestLeaky() {
    REQUEST_BUFFER.get().add(new byte[100 * 1024]);
}

// FIX: dọn trong finally
static void handleRequestFixed() {
    try {
        REQUEST_BUFFER.get().add(new byte[100 * 1024]);
    } finally {
        REQUEST_BUFFER.remove();
    }
}
```

Ngoài đời: MDC logging, security context, transaction context, `SimpleDateFormat` gắn theo thread.
Với web app, đặt `remove()` trong filter/interceptor bao ngoài toàn bộ request. Ngoài leak bộ nhớ, quên `remove()`
còn làm **rò dữ liệu giữa các request** (request sau thấy user của request trước) — lỗi bảo mật.

### Kiểm chứng

```bash
java -Xmx64m -cp out ThreadLocalLeakInThreadPoolDemo fixed
```

```
requests=1400 heapUsed=11MB
requests=1500 heapUsed=21MB
DONE - heap on dinh, khong OOM
```

---

## Demo 5 — Leak ngoài heap (direct buffer)

File: [DirectBufferOffHeapLeakDemo.java](demos/DirectBufferOffHeapLeakDemo.java)

### Chạy bản lỗi (T1)

```bash
java -Xmx64m -XX:MaxDirectMemorySize=64m -XX:NativeMemoryTracking=summary \
     -DpauseMs=300 -cp out DirectBufferOffHeapLeakDemo leak
```

```
connections=30 heapUsed=2MB directUsed=31MB
connections=40 heapUsed=2MB directUsed=41MB
connections=50 heapUsed=2MB directUsed=51MB
connections=60 heapUsed=2MB directUsed=61MB
Exception in thread "main" java.lang.OutOfMemoryError: Cannot reserve 1048576 bytes of direct buffer memory (allocated: 67108864, limit: 67108864)
	at java.base/java.nio.Bits.reserveMemory(Bits.java:178)
	at java.base/java.nio.DirectByteBuffer.<init>(DirectByteBuffer.java:111)
	at java.base/java.nio.ByteBuffer.allocateDirect(ByteBuffer.java:360)
	at DirectBufferOffHeapLeakDemo.handleConnectionLeaky(DirectBufferOffHeapLeakDemo.java:35)
```

Quan sát: `heapUsed` đứng yên ở 2MB trong khi `directUsed` leo tới trần. Nếu chỉ nhìn dashboard heap, bạn sẽ
kết luận ứng dụng hoàn toàn khoẻ. Thông báo lỗi ghi rõ `allocated` và `limit`.

### Trace khi đang chạy (T2)

```bash
jcmd <pid> VM.native_memory baseline
# chờ ~8 giây
jcmd <pid> VM.native_memory summary.diff | grep -E "^Total|Java Heap|Other"
ps -o rss= -p <pid>                       # RSS thực tế (KB) theo hệ điều hành
```

```
Total: reserved=1579952KB +28182KB, committed=223392KB +28182KB
-                 Java Heap (reserved=65536KB, committed=65536KB)
-                     Other (reserved=46080KB +29696KB, committed=46080KB +29696KB)
```

`Other` (nơi tính direct buffer) tăng, heap không đổi. Để biết **ai** giữ buffer: heap dump vẫn dùng được, vì mỗi
vùng native có một object `java.nio.DirectByteBuffer` nhỏ trên heap đại diện. Trong MAT, tìm class
`java.nio.DirectByteBuffer` trong Histogram → Path To GC Roots → sẽ dẫn về `LEAKED_CONNECTION_BUFFERS`.

### Nguyên nhân và cách fix

```java
// LỖI: mỗi kết nối cấp buffer mới và giữ lại mãi
static void handleConnectionLeaky() {
    ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
    buffer.putLong(System.nanoTime());
    LEAKED_CONNECTION_BUFFERS.add(buffer);
}

// FIX: dùng lại buffer
static void handleConnectionFixed() {
    REUSABLE_BUFFER.clear();
    REUSABLE_BUFFER.putLong(System.nanoTime());
}
```

Bộ nhớ native của direct buffer chỉ được trả khi object `DirectByteBuffer` trên heap bị GC. Nên có hai cách chết:
giữ tham chiếu (như demo), hoặc không giữ nhưng heap rộng tới mức GC hiếm khi chạy, buffer rác chưa kịp dọn.
Ngoài đời: dùng buffer pool có trần (Netty `PooledByteBufAllocator` và nhớ `release()`), đặt `-XX:MaxDirectMemorySize`
rõ ràng, và trong container phải tính `heap + direct + metaspace + stack` dưới memory limit.

### Kiểm chứng

```bash
java -Xmx64m -XX:MaxDirectMemorySize=64m -cp out DirectBufferOffHeapLeakDemo fixed
```

```
connections=190 heapUsed=3MB directUsed=1MB
connections=200 heapUsed=3MB directUsed=1MB
DONE - direct memory on dinh, khong OOM
```

---

## Tổng kết: dấu hiệu nào dẫn tới công cụ nào

| Thấy gì | Demo | Công cụ quyết định | Dấu vết nhận dạng |
|---------|------|--------------------|-------------------|
| Heap tăng, có class nghiệp vụ tăng số instance | 1 | Histogram ×2 → heap dump | Field `static` trên đường tới GC root |
| Heap tăng, chỉ thấy `byte[]`/`String` tăng | 4 | Heap dump | `Thread → threadLocals` trên đường tới GC root |
| Stack trace lặp một đoạn | 2 | Đọc stack trace | Dòng/cụm dòng lặp |
| Số class nạp tăng mãi | 3 | `VM.classloader_stats` | Nhiều ClassLoader cùng `Type` |
| Heap phẳng, RSS tăng | 5 | NMT `summary.diff` | Mục `Other`/`Thread`/`Class` có dấu `+` |

## Bài tập tự làm

1. Demo 1: đổi `MAX_CACHED_SESSIONS` thành 1000 rồi chạy `fixed` với `-Xmx64m`. Chuyện gì xảy ra, vì sao? (Gợi ý: 1000 × 100KB.)
2. Demo 4: bỏ `finally`, để `remove()` ngay sau `add`, rồi cho `add` ném exception ngẫu nhiên. Leak có quay lại không?
3. Demo 2: tìm số `-Dnodes` lớn nhất chạy được với stack mặc định, rồi với `-Xss2m`. Tỉ lệ có tuyến tính không?
4. Demo 3: trong bản lỗi, bỏ dòng `LEAKY_PLUGIN_REGISTRY.add(...)`. Còn OOM không? Giải thích bằng quy tắc gỡ class.
5. Demo 5: chạy bản lỗi không có `-XX:MaxDirectMemorySize`. Trần lúc này là bao nhiêu, lấy từ đâu?
