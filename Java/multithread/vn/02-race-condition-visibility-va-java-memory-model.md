# 02 — Race condition, visibility và Java Memory Model

Mục tiêu: sau bài này bạn gọi tên được ba loại lỗi gốc của code đa luồng, hiểu vì sao chúng xảy ra ở tầng CPU và JVM,
và biết `volatile` giải quyết được gì, không giải quyết được gì.

## 1. Lỗi đa luồng khác lỗi thường ở đâu

| | Lỗi thông thường | Lỗi đa luồng |
|---|---|---|
| Tái hiện | Cùng đầu vào, cùng kết quả | Lúc có lúc không, phụ thuộc thời điểm |
| Test | Bắt được | Test xanh hàng nghìn lần rồi production vẫn sai |
| Debug | Đặt breakpoint là thấy | Đặt breakpoint làm đổi thời điểm, lỗi biến mất |
| Triệu chứng | Exception rõ ràng | Số liệu lệch âm thầm, treo, hiếm khi có exception |

Vì vậy với code đa luồng, **phải đúng ngay từ thiết kế**. Không thể "test cho ra lỗi rồi sửa".

Mọi lỗi đều quy về một điều kiện: **có trạng thái thay đổi được (mutable) mà hơn một thread truy cập, và ít nhất một
thread ghi**. Bỏ được một trong ba yếu tố (dùng chung, thay đổi được, có ghi) là hết lỗi; đây là cơ sở cho các
chiến lược ở bài 4.

Có ba cách hỏng: **atomicity**, **visibility**, **ordering**.

## 2. Atomicity: thao tác bị chen ngang

Demo: [RaceConditionCounterDemo.java](../demos/RaceConditionCounterDemo.java). 8 thread, mỗi thread cộng 1 triệu lần vào một biến chung.

```java
static int plainCounter = 0;
...
plainCounter++;
```

```
mode=broken threads=8 incrementsPerThread=1,000,000
expected=8,000,000 actual=1,963,442 lost=6,036,558
RESULT: WRONG (lost updates)
```

Mất hơn 6 triệu lần cộng, không có exception nào. Lý do: `counter++` trông như một bước nhưng là ba:

```
1. đọc giá trị của counter vào thanh ghi
2. cộng 1
3. ghi kết quả trở lại counter
```

Hai thread xen vào nhau:

```
Thread A                    Thread B                 counter
đọc 41                                                 41
                            đọc 41                     41
cộng → 42                                              41
                            cộng → 42                  41
ghi 42                                                 42
                            ghi 42                     42   ← hai lần cộng, chỉ tăng 1
```

Đây là **race condition**: kết quả phụ thuộc vào thứ tự chạy tình cờ của các thread. Hai dạng hay gặp:

| Dạng | Ví dụ | Vì sao sai |
|------|-------|-----------|
| Read-modify-write | `count++`, `balance -= amount` | Giá trị đọc được đã cũ lúc ghi |
| Check-then-act | `if (!map.containsKey(k)) map.put(k, v)`, `if (instance == null) instance = new ...` | Điều kiện đã kiểm tra không còn đúng lúc hành động |

Sửa bằng cách biến cả chuỗi thao tác thành **nguyên tử** (không ai chen được vào giữa): dùng khoá (bài 3) hoặc lớp
atomic (bài 4). Bản fix của demo:

```java
static final AtomicInteger atomicCounter = new AtomicInteger();
...
atomicCounter.incrementAndGet();
```

```
expected=8,000,000 actual=8,000,000 lost=0
RESULT: CORRECT
```

## 3. Visibility: ghi rồi mà thread khác không thấy

Demo: [VisibilityStopFlagDemo.java](../demos/VisibilityStopFlagDemo.java). Một worker quay vòng lặp cho đến khi cờ `running` thành `false`.

```java
static boolean plainRunning = true;

// worker
while (plainRunning) {
    iterations++;
}

// main, sau 1 giây
plainRunning = false;
```

```
mode=broken
main: set running=false
RESULT: WRONG (worker is still spinning 3s after the flag changed)
```

`main` đã ghi `false`, nhưng worker quay mãi. Không có thao tác nào bị chen ngang ở đây; vấn đề là worker **không
bao giờ đọc lại** biến đó. Hai nguyên nhân chồng lên nhau:

- **Phần cứng**: mỗi core có cache riêng. Giá trị ghi ở core này không lập tức hiện ở core khác.
- **JIT**: trình biên dịch thấy trong vòng lặp không ai ghi `plainRunning`, và theo đặc tả nó không buộc phải tính tới
  thread khác. Nó đọc biến một lần rồi biến vòng lặp thành `while (true)`.

Thread dump của worker lúc đó: đang `RUNNABLE`, ăn trọn một core, đứng mãi ở dòng vòng lặp.

```
"spinner" #20 daemon prio=5 cpu=2169.71ms elapsed=2.17s ... runnable
   java.lang.Thread.State: RUNNABLE
	at VisibilityStopFlagDemo.lambda$main$0(VisibilityStopFlagDemo.java:33)
```

Lỗi này đặc biệt khó chịu vì nó chỉ hiện ra khi JIT đã tối ưu vòng lặp: chạy trong debugger hoặc thêm một dòng
`System.out.println` vào vòng lặp là "hết lỗi". Đừng coi đó là cách sửa.

Sửa bằng `volatile`:

```java
static volatile boolean volatileRunning = true;
```

```
mode=fixed
main: set running=false
worker: saw the stop flag after 1,753,059,506 iterations
RESULT: CORRECT (worker stopped)
```

## 4. Ordering: lệnh bị đảo thứ tự

Trình biên dịch và CPU được phép đổi thứ tự các lệnh miễn là **kết quả trong một thread** không đổi. Thread khác thì
có thể thấy trạng thái trung gian mà theo code nguồn "không thể xảy ra".

Ví dụ kinh điển là double-checked locking viết sai:

```java
class Config {
    private static Config instance;            // SAI: thiếu volatile

    static Config get() {
        if (instance == null) {                // (1) kiểm tra không khoá
            synchronized (Config.class) {
                if (instance == null) {
                    instance = new Config();   // (2)
                }
            }
        }
        return instance;
    }
}
```

`instance = new Config()` gồm: cấp phát bộ nhớ, chạy constructor, gán tham chiếu. Nếu phép gán được xếp trước khi
constructor chạy xong, thread khác ở bước (1) thấy `instance != null` và dùng một object **chưa khởi tạo xong**.

Sửa: khai báo `private static volatile Config instance`. Tốt hơn nữa, tránh hẳn mẫu này:

```java
class Config {
    private Config() {}
    private static class Holder { static final Config INSTANCE = new Config(); }
    static Config get() { return Holder.INSTANCE; }   // JVM bảo đảm khởi tạo class an toàn và lười
}
```

## 5. Java Memory Model và happens-before

Java Memory Model (JMM) là phần đặc tả trả lời câu hỏi: **khi nào một thread chắc chắn thấy giá trị do thread khác ghi?**

Câu trả lời: khi giữa lệnh ghi và lệnh đọc có quan hệ **happens-before**. Không có quan hệ đó thì JVM được phép cho
thread đọc thấy giá trị cũ, vô thời hạn.

Các quy tắc tạo ra happens-before cần nhớ:

| Quy tắc | Nội dung |
|---------|----------|
| Trong một thread | Lệnh trước happens-before lệnh sau |
| Khoá | Nhả khoá happens-before lần lấy **cùng khoá đó** tiếp theo |
| `volatile` | Ghi biến volatile happens-before mọi lần đọc biến đó sau đấy |
| Khởi động thread | `thread.start()` happens-before mọi lệnh trong thread đó |
| Kết thúc thread | Mọi lệnh trong thread happens-before việc `thread.join()` trả về |
| `final` | Field `final` được gán trong constructor thì mọi thread thấy đúng giá trị, miễn là `this` không bị lộ ra trong constructor |
| Bắc cầu | A trước B, B trước C thì A trước C |

Hệ quả thực hành:

- Đọc và ghi cùng một biến chung phải cùng đi qua **một** cơ chế đồng bộ: cùng một khoá, hoặc `volatile`, hoặc atomic.
  Chỉ đồng bộ phía ghi là chưa đủ.
- `synchronized` giải quyết cả atomicity lẫn visibility. Đó là lý do getter của một field được bảo vệ bằng khoá cũng
  phải `synchronized`.
- Kết quả một thread tính ra, đọc sau khi `join()` thì an toàn mà không cần `volatile`. Demo 1 đọc `plainCounter`
  sau `join()` theo đúng quy tắc này.

## 6. `volatile`: làm được gì, không làm được gì

| | `volatile` | `synchronized` / `Lock` | Atomic |
|---|---|---|---|
| Visibility | Có | Có | Có |
| Chống đảo thứ tự | Có | Có | Có |
| Atomicity cho thao tác ghép (`++`, check-then-act) | **Không** | Có | Có (trong phạm vi một biến) |
| Chặn thread | Không | Có | Không |

`volatile int counter; counter++` **vẫn mất cập nhật**: mỗi lần đọc, mỗi lần ghi đều "nhìn thấy nhau", nhưng ba bước
của `++` vẫn bị chen ngang.

`volatile` dùng đúng khi thoả cả hai điều kiện:

1. Giá trị ghi vào không phụ thuộc giá trị cũ (gán thẳng `flag = false`, `config = newConfig`), hoặc chỉ có một thread ghi.
2. Biến không tham gia vào một ràng buộc chung với biến khác (ví dụ `lower <= upper`).

Trường hợp điển hình: cờ dừng, cờ trạng thái, tham chiếu tới một object bất biến được thay cả cụm.

## 7. Tóm tắt chẩn đoán

| Triệu chứng | Loại lỗi | Hướng sửa |
|-------------|----------|-----------|
| Số đếm, số dư lệch dần khi tải cao | Atomicity (read-modify-write) | Khoá hoặc atomic |
| Thỉnh thoảng tạo trùng, khởi tạo hai lần | Atomicity (check-then-act) | Khoá, `computeIfAbsent`, `putIfAbsent` |
| Thread không dừng, không nhận cấu hình mới | Visibility | `volatile` hoặc đọc trong khoá |
| Lỗi biến mất khi debug hoặc thêm log | Visibility hoặc ordering | Như trên; đừng tin vào "hết lỗi" |
| Thấy object ở trạng thái nửa vời | Ordering, publication không an toàn | `final`, `volatile`, không để lộ `this` trong constructor |

## 8. Tự kiểm tra

1. `volatile int count; count++` từ 8 thread có ra kết quả đúng không? Vì sao?
2. Trong demo visibility, thêm `System.out.println` vào vòng lặp thì chương trình dừng được. Bug đã hết chưa?
3. Thread A ghi `x = 5` trong khối `synchronized (lock)`. Thread B đọc `x` không dùng khoá. B có chắc thấy 5 không?
4. Vì sao đọc `plainCounter` sau khi `join()` mọi worker là an toàn dù biến không `volatile`?

Đáp án ngắn: (1) Không: `volatile` không làm `++` thành nguyên tử. (2) Chưa: `println` có khoá bên trong và làm JIT
không tối ưu được vòng lặp; bug chỉ bị che. (3) Không: happens-before của khoá chỉ áp dụng cho thread lấy **cùng khoá**.
(4) Vì `join()` trả về tạo quan hệ happens-before với mọi lệnh của thread đã kết thúc.

Bài tiếp theo: [03 — synchronized, Lock và deadlock](03-synchronized-lock-va-deadlock.md).
