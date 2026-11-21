# 03 — Công cụ và quy trình trace

Output trong bài này lấy từ các demo trong `demos/`, chạy trên JDK 21.0.5 (G1, macOS).
Số liệu trên máy bạn sẽ khác đôi chút, hình dạng thì giống.

## 1. Quy trình 6 bước

```
1. Đọc thông báo lỗi        → xác định vùng nhớ (bảng tra bài 02)
2. Xác nhận xu hướng        → jstat / GC log: đáy heap có tăng dần không?
3. Tìm loại object tăng     → 2 lần class histogram cách nhau vài phút, so sánh
4. Tìm ai đang giữ          → heap dump + MAT: Dominator Tree, Path to GC Roots
5. Sửa                      → cắt đường tham chiếu, đặt trần, đóng tài nguyên
6. Kiểm chứng               → chạy lại cùng tải, đáy heap phải đi ngang
```

Bước 4 là bước duy nhất cho câu trả lời chắc chắn. Bước 2–3 rẻ, làm được trên production đang chạy,
giúp bạn biết cần tìm gì trong dump.

## 2. Chuẩn bị sẵn trước khi có sự cố

Bật thường trực trên mọi môi trường:

```bash
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/dumps          # thư mục có sẵn, trống ít nhất bằng -Xmx
-XX:+ExitOnOutOfMemoryError
-Xlog:gc*:file=/var/log/app/gc.log:time,uptime:filecount=5,filesize=20m
```

Không có heap dump lúc chết thì chỉ còn cách chờ sự cố lặp lại.

## 3. Bộ công cụ

Tất cả có sẵn trong JDK, trừ MAT.

| Công cụ | Dùng để | Ảnh hưởng tới app đang chạy |
|---------|---------|-----------------------------|
| `jcmd -l` | Liệt kê process Java và PID | Không |
| `jstat -gcutil <pid> <ms>` | Theo dõi % từng vùng heap và số lần GC | Không đáng kể |
| `jcmd <pid> GC.heap_info` | Ảnh chụp nhanh heap + Metaspace | Không đáng kể |
| `jcmd <pid> GC.class_histogram` | Đếm instance và byte theo class | Dừng ngắn (chạy một lần Full GC) |
| `jcmd <pid> GC.heap_dump <file>` | Chụp toàn bộ heap ra file `.hprof` | Dừng app trong lúc ghi; file cỡ bằng heap |
| `jcmd <pid> Thread.print` | Thread dump | Dừng rất ngắn |
| `jcmd <pid> VM.native_memory` | Bộ nhớ ngoài heap (cần bật NMT lúc khởi động) | Không đáng kể |
| `jcmd <pid> VM.classloader_stats` | Thống kê theo ClassLoader | Dừng ngắn |
| `jcmd <pid> VM.metaspace` | Chi tiết Metaspace | Dừng ngắn |
| Eclipse MAT | Phân tích file `.hprof` | Chạy trên máy bạn, không đụng app |
| JDK Flight Recorder (`-XX:StartFlightRecording`) | Ghi liên tục cấp phát, GC, thread; overhead thấp | Khoảng 1–2% |

`jcmd <pid> help` liệt kê mọi lệnh process đó hỗ trợ.

Về JFR: sự kiện `OldObjectSample` có thể chỉ ra object nghi leak, nhưng nó lấy mẫu chứ không ghi đủ.
Thử với demo ThreadLocal trong bộ này, nó chỉ bắt được 1 mẫu và không phải object leak. Dùng JFR để xem
xu hướng; kết luận thì dựa vào heap dump. Thêm nữa, nếu JVM chết vì OOM thì file JFR ghi lúc thoát có
thể rỗng — hãy `jcmd <pid> JFR.dump` khi process còn sống.

## 4. Bước 2 — Xác nhận xu hướng

### `jstat`

```bash
jstat -gcutil <pid> 2000        # in một dòng mỗi 2 giây
```

```
  S0     S1     E      O      M     CCS    YGC     YGCT     FGC    FGCT     CGC    CGCT       GCT
     -      -   0,00  54,91  97,51  86,95      1     0,004     1     0,006     0     0,000     0,010
     -      -  10,71  54,91  97,51  86,95      1     0,004     1     0,006     0     0,000     0,010
     -      -  28,57  54,91  97,51  86,95      1     0,004     1     0,006     0     0,000     0,010
     -      -  46,43  54,91  97,51  86,95      1     0,004     1     0,006     0     0,000     0,010
```

| Cột | Nghĩa | Nhìn gì |
|-----|-------|---------|
| `E` | % Eden đang dùng | Tăng rồi rơi về 0 mỗi lần Young GC — bình thường |
| `O` | % Old đang dùng | **Cột quan trọng nhất.** Tăng dần qua nhiều giờ và không giảm sau Full GC = leak |
| `M`, `CCS` | % Metaspace / class space đã commit đang dùng | Gần 100% là bình thường, không phải sắp hết |
| `YGC` / `FGC` | Số lần Young / Full GC | `FGC` tăng liên tục = heap đang ngạt |
| `GCT` | Tổng giây đã tiêu cho GC | Tăng nhanh so với thời gian chạy = GC chiếm CPU |

Đoạn trên chỉ dài 6 giây nên mới thấy `E` leo; trên hệ thống thật hãy theo dõi `O` qua nhiều chu kỳ GC.

### GC log

Cuối GC log của demo 1, ngay trước khi OOM:

```
[26.709s][info][gc] GC(31) Pause Full (G1 Compaction Pause) 62M->62M(64M) 3.464ms
[26.714s][info][gc] GC(33) Pause Full (G1 Compaction Pause) 62M->62M(64M) 4.416ms
[26.717s][info][gc] GC(35) Pause Full (G1 Compaction Pause) 62M->62M(64M) 3.236ms
[26.721s][info][gc] GC(36) Pause Full (G1 Compaction Pause) 62M->62M(64M) 3.954ms
```

Đọc `62M->62M(64M)` là: trước GC 62MB → sau GC 62MB, trên tổng 64MB. Full GC nối đuôi nhau mà
không thu được byte nào: mọi thứ trong heap đều đang bị giữ. Đây là hình ảnh điển hình của leak ở
giai đoạn cuối. Với ứng dụng khoẻ, số sau mũi tên thấp hơn hẳn số trước.

## 5. Bước 3 — Tìm loại object đang tăng

Chụp histogram hai lần, cách nhau đủ lâu để thấy khác biệt:

```bash
jcmd <pid> GC.class_histogram > histo-1.txt
# chờ vài phút
jcmd <pid> GC.class_histogram > histo-2.txt
```

Đầu bảng gần như luôn là `[B` (`byte[]`), `String`, `Object[]` — chúng là nguyên liệu của mọi thứ nên
không nói được ai là chủ:

```
 num     #instances         #bytes  class name (module)
-------------------------------------------------------
   1:         17349       15388408  [B (java.base@21.0.5)
   2:         16194         388656  java.lang.String (java.base@21.0.5)
```

Hãy lọc theo **class của chính ứng dụng** và so số instance giữa hai lần:

```bash
grep UserSession histo-1.txt histo-2.txt
```

```
histo-1:  41:           120           2880  HeapLeakUnboundedCacheDemo$UserSession
histo-2:  19:           307           7368  HeapLeakUnboundedCacheDemo$UserSession
```

120 → 307 instance sau 8 giây và không bao giờ giảm: đây là nghi phạm. Cột `#bytes` chỉ là kích thước
bản thân object (shallow), 24 byte mỗi `UserSession` — phần 100KB nằm ở `byte[]` mà nó trỏ tới.
Vì vậy đừng xếp hạng theo `#bytes`, hãy nhìn **số instance tăng**.

Ký hiệu tên class: `[B` = `byte[]`, `[C` = `char[]`, `[I` = `int[]`, `[Ljava.lang.Object;` = `Object[]`.

## 6. Bước 4 — Heap dump và MAT

### Lấy dump

```bash
# Tự động khi OOM (đã bật cờ ở mục 2) → java_pid<pid>.hprof
# Hoặc chủ động trên process đang sống:
jcmd <pid> GC.heap_dump /duong/dan/tuyet/doi/app.hprof
```

Cẩn trọng: app dừng trong lúc ghi (heap vài chục GB có thể mất hàng phút), và file chứa nguyên dữ liệu
người dùng, mật khẩu, token đang nằm trong RAM. Xử lý file dump như dữ liệu production.

### Phân tích bằng Eclipse MAT

Tải tại <https://eclipse.dev/mat/>. Dump lớn thì tăng `-Xmx` của MAT trong `MemoryAnalyzer.ini`.

Hai khái niệm cần nắm:

- **Shallow heap**: kích thước riêng object đó.
- **Retained heap**: tổng bộ nhớ sẽ được giải phóng nếu object đó biến mất. Đây là con số để xếp hạng.

Trình tự làm việc:

1. **Leak Suspects Report** (MAT gợi ý khi mở file): thường chỉ đúng ngay một object chiếm phần lớn heap.
2. **Dominator Tree**, sắp theo Retained Heap giảm dần: object đầu bảng là thứ đang "sở hữu" bộ nhớ.
3. Chuột phải vào nó → **Path To GC Roots → exclude all phantom/weak/soft etc. references**:
   chuỗi tham chiếu từ GC root tới object. Mắt xích nằm trong code của bạn là chỗ cần sửa.
4. **Histogram** → nhóm theo package của bạn, khi cần đếm theo class giống bước 3.

Đường tới GC root cần tìm ở từng demo:

| Demo | Chuỗi tham chiếu |
|------|------------------|
| 1 | `class HeapLeakUnboundedCacheDemo` → static `LEAKY_CACHE` → `HashMap` → `HashMap$Node[]` → `UserSession` → `byte[]` |
| 4 | `Thread pool-1-thread-N` → `threadLocals` (`ThreadLocalMap`) → `Entry.value` → `ArrayList` → `byte[]` |

Một số IDE (IntelliJ IDEA, VisualVM) cũng mở được `.hprof` với các khung nhìn tương tự.

## 7. Trace bộ nhớ ngoài heap

### Direct buffer và native: Native Memory Tracking

Phải bật khi khởi động: `-XX:NativeMemoryTracking=summary`.

```bash
jcmd <pid> VM.native_memory baseline        # đặt mốc
# chờ
jcmd <pid> VM.native_memory summary.diff    # xem phần chênh so với mốc
```

Kết quả với demo 5 sau khoảng 8 giây:

```
Total: reserved=1579952KB +28182KB, committed=223392KB +28182KB
-                 Java Heap (reserved=65536KB, committed=65536KB)
-                     Class (reserved=1048689KB, committed=305KB)
-                    Thread (reserved=37130KB, committed=37130KB)
-                     Other (reserved=46080KB +29696KB, committed=46080KB +29696KB)
```

`Java Heap` không đổi, `Other` tăng 29MB. Direct buffer được tính vào mục `Other`.
Dòng nào có dấu `+` lớn dần qua các lần diff là nơi đang leak:

| Mục NMT tăng | Nghĩ tới |
|--------------|----------|
| `Other` | Direct buffer |
| `Thread` | Tạo thread không giới hạn |
| `Class` | Leak ClassLoader |
| `Code` | Code cache |
| Không mục nào tăng mà RSS vẫn tăng | Thư viện native ngoài tầm theo dõi của JVM |

### Metaspace: thống kê ClassLoader

```bash
jcmd <pid> VM.classloader_stats
```

```
ClassLoader         Parent              CLD*               Classes   ChunkSz   BlockSz  Type
0x000000e001000bf8  0x000000e00006ad70  0x000060000203a300       1      6144      1368  MetaspaceClassLoaderLeakDemo$PluginClassLoader
0x000000e001000bf8  0x000000e00006ad70  0x000060000202ce60       1      6144      1368  MetaspaceClassLoaderLeakDemo$PluginClassLoader
...
Total = 884                                                   1851   9944064   2009888
```

Hàng trăm dòng cùng một `Type`, mỗi cái nạp đúng 1 class: cùng một class đang bị nạp lặp lại bởi các
ClassLoader khác nhau. Xác nhận bằng histogram:

```
  10:           902          72160  MetaspaceClassLoaderLeakDemo$PluginClassLoader
```

Ứng dụng khoẻ có số ClassLoader gần như cố định sau khi khởi động xong. Muốn biết ai giữ các ClassLoader
đó thì vẫn dùng heap dump + Path To GC Roots như mục 6 (ClassLoader là object bình thường trên heap).

### Thread

```bash
jcmd <pid> Thread.print | grep -c '^"'                 # tổng số thread
jcmd <pid> Thread.print | grep '^"' | cut -d'"' -f2 | sed 's/[0-9]*$//' | sort | uniq -c | sort -rn
```

Lệnh thứ hai gom theo tiền tố tên thread: nhóm nào có số lượng tăng mãi là pool bị tạo lặp hoặc không shutdown.

## 8. Bước 6 — Kiểm chứng sau khi sửa

"Không thấy OOM nữa" chưa phải bằng chứng; có thể chỉ là chưa chạy đủ lâu. Kiểm chứng đúng nghĩa:

1. Chạy lại đúng kịch bản tải đã gây lỗi, thời gian dài hơn lúc trước nó chết.
2. Đáy heap sau GC đi ngang (`jstat` cột `O`, hoặc GC log).
3. Số instance của class nghi phạm ổn định giữa hai lần histogram.

Thực hành toàn bộ quy trình: [04 — Thực hành demo](04-thuc-hanh-demo-tim-va-fix.md).
