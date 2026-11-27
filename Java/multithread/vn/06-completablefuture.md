# 06 — CompletableFuture

Mục tiêu: sau bài này bạn ghép được nhiều tác vụ bất đồng bộ mà không chặn thread, xử lý lỗi và timeout đúng cách,
và biết các bẫy của `CompletableFuture` trước khi gặp chúng trong production.

## 1. Vì sao `Future` chưa đủ

`Future` (bài 5) chỉ có một cách lấy kết quả: `get()`, và nó **chặn**. Muốn "khi xong thì làm tiếp việc khác",
"chờ cả ba rồi gộp lại", "lỗi thì dùng giá trị dự phòng" đều phải tự viết bằng tay và tốn thread ngồi chờ.

`CompletableFuture` (CF) cho phép **mô tả cả chuỗi xử lý trước**, rồi để nó tự chạy khi dữ liệu sẵn sàng.

| | `Future` | `CompletableFuture` |
|---|---|---|
| Lấy kết quả | Chỉ `get()` chặn | Gắn callback, hoặc `join()` ở cuối |
| Nối bước tiếp theo | Không | `thenApply`, `thenCompose`... |
| Gộp nhiều kết quả | Tự viết | `thenCombine`, `allOf`, `anyOf` |
| Xử lý lỗi | `try/catch` quanh `get()` | `exceptionally`, `handle` |
| Timeout | `get(timeout)` | `orTimeout`, `completeOnTimeout` |

## 2. Demo: tuần tự và song song

Demo: [CompletableFutureCompositionDemo.java](../demos/CompletableFutureCompositionDemo.java). Trang dashboard cần ba thứ
không phụ thuộc nhau: hồ sơ user (300 ms), đơn hàng (400 ms), gợi ý (500 ms).

Tuần tự, mỗi lời gọi chờ lời gọi trước:

```java
static Dashboard loadSequential() {
    return new Dashboard(fetchUser(), fetchOrders(), fetchRecommendations(false));
}
```

```
mode=sequential
Dashboard[user=an, orders=[order-1, order-2], recommendations=[book-7, book-9]]
elapsed=1212 ms
```

Song song, cả ba khởi động cùng lúc:

```java
ExecutorService ioPool = Executors.newFixedThreadPool(3);

CompletableFuture<String> user =
        CompletableFuture.supplyAsync(CompletableFutureCompositionDemo::fetchUser, ioPool);
CompletableFuture<List<String>> orders =
        CompletableFuture.supplyAsync(CompletableFutureCompositionDemo::fetchOrders, ioPool);
CompletableFuture<List<String>> recommendations =
        CompletableFuture.supplyAsync(() -> fetchRecommendations(recommendationsFail), ioPool)
                .orTimeout(2, TimeUnit.SECONDS)
                .exceptionally(error -> {
                    System.out.println("recommendations failed: " + error.getMessage() + " -> using fallback");
                    return List.of("bestseller-1");
                });

return user
        .thenCombine(orders, (u, o) -> new Dashboard(u, o, List.of()))
        .thenCombine(recommendations, (d, r) -> new Dashboard(d.user(), d.orders(), r))
        .join();
```

```
mode=parallel
Dashboard[user=an, orders=[order-1, order-2], recommendations=[book-7, book-9]]
elapsed=510 ms
```

1212 ms xuống 510 ms: tổng thời gian bằng lời gọi **chậm nhất** thay vì **tổng** các lời gọi.

Khi dịch vụ gợi ý sập, trang vẫn hiện với dữ liệu dự phòng:

```
mode=failure
recommendations failed: java.lang.IllegalStateException: recommendation service is down -> using fallback
Dashboard[user=an, orders=[order-1, order-2], recommendations=[bestseller-1]]
elapsed=519 ms
```

## 3. Bộ từ vựng

```java
CompletableFuture<String> user = CompletableFuture.supplyAsync(() -> findUser(1), ioPool);

CompletableFuture<String> upper = user.thenApply(String::toUpperCase);                   // biến đổi
CompletableFuture<List<String>> orders = user.thenCompose(Snippets::findOrdersAsync);    // nối bước bất đồng bộ
CompletableFuture<String> summary =
        user.thenCombine(orders, (u, o) -> u + " has " + o.size() + " orders");          // gộp hai kết quả
CompletableFuture<Void> all = CompletableFuture.allOf(upper, orders, summary);           // chờ tất cả
CompletableFuture<Object> first = CompletableFuture.anyOf(upper, summary);               // cái nào xong trước
```

| Method | Nhận | Trả về | Tương đương |
|--------|------|--------|-------------|
| `supplyAsync(supplier, executor)` | Việc có kết quả | `CF<T>` | Bắt đầu chuỗi |
| `runAsync(runnable, executor)` | Việc không kết quả | `CF<Void>` | Bắt đầu chuỗi |
| `thenApply(fn)` | `T -> U` | `CF<U>` | `map` |
| `thenCompose(fn)` | `T -> CF<U>` | `CF<U>` | `flatMap` |
| `thenAccept(consumer)` | `T -> void` | `CF<Void>` | Bước cuối: dùng kết quả |
| `thenCombine(other, fn)` | Hai CF độc lập | `CF<V>` | Gộp hai nhánh song song |
| `allOf(cf...)` | Nhiều CF | `CF<Void>` | Chờ tất cả; tự lấy từng kết quả bằng `join()` |
| `anyOf(cf...)` | Nhiều CF | `CF<Object>` | Cái đầu tiên hoàn thành |

`thenApply` hay `thenCompose`: nếu hàm của bạn trả về một giá trị thường, dùng `thenApply`. Nếu nó trả về một
`CompletableFuture` khác (gọi thêm một việc bất đồng bộ), dùng `thenCompose`; dùng nhầm `thenApply` sẽ cho ra
`CF<CF<U>>`.

**Độc lập thì `thenCombine`/`allOf` (song song); phụ thuộc thì `thenCompose` (nối tiếp).**

## 4. Callback chạy trên thread nào

| Dạng | Callback chạy trên |
|------|--------------------|
| `thenApply(fn)` | Thread vừa hoàn thành bước trước; hoặc **thread gọi `thenApply`** nếu bước trước đã xong sẵn |
| `thenApplyAsync(fn)` | `ForkJoinPool.commonPool()` |
| `thenApplyAsync(fn, executor)` | Executor bạn chỉ định |

Hệ quả: callback không có hậu tố `Async` có thể chạy trên một thread bạn không ngờ tới (thread I/O của HTTP client,
thread của caller). Callback nặng hoặc có chặn thì dùng bản `Async` kèm executor rõ ràng:

```java
CompletableFuture<Void> logged = summary.thenAcceptAsync(System.out::println, ioPool);
```

### Luôn chỉ định executor cho việc chặn

`supplyAsync(supplier)` không có tham số executor sẽ chạy trên **common pool**: dùng chung toàn JVM, chỉ có
(số core − 1) thread, thiết kế cho tính toán. Đưa lời gọi database hay HTTP vào đó thì vài request chậm là đủ chiếm
hết pool, làm treo mọi parallel stream và mọi `CompletableFuture` khác trong ứng dụng.

```java
CompletableFuture.supplyAsync(() -> callRemote());            // SAI cho I/O: dùng common pool
CompletableFuture.supplyAsync(() -> callRemote(), ioPool);    // ĐÚNG
```

## 5. Xử lý lỗi

Exception ở bất kỳ bước nào làm các bước `then...` phía sau bị bỏ qua, và CF kết thúc ở trạng thái lỗi, cho tới khi
gặp một bước xử lý lỗi. Giống `try/catch` trải dọc theo chuỗi.

```java
CompletableFuture<String> safe = summary
        .orTimeout(2, TimeUnit.SECONDS)
        .exceptionally(error -> "summary unavailable");                       // chỉ chạy khi lỗi

CompletableFuture<String> handled =
        summary.handle((value, error) -> error == null ? value : "fallback"); // luôn chạy
```

| Method | Chạy khi | Dùng để |
|--------|----------|---------|
| `exceptionally(fn)` | Có lỗi | Trả giá trị dự phòng |
| `handle((value, error) -> ...)` | Luôn luôn | Đổi cả kết quả lẫn lỗi thành một giá trị |
| `whenComplete((value, error) -> ...)` | Luôn luôn | Tác dụng phụ (log, metrics); không đổi kết quả |

Ba điều cần biết:

- **Exception bị bọc.** Lỗi gốc nằm trong `CompletionException`. Trong output demo, `error.getMessage()` in ra
  `java.lang.IllegalStateException: recommendation service is down`: tên class đứng trước vì đó là thông điệp của lớp bọc.
  Muốn lỗi gốc thì dùng `error.getCause()`.
- **Lỗi không ai xử lý thì im lặng.** CF không ai gọi `join()`/`get()` và không có `exceptionally`/`handle` sẽ nuốt
  exception hoàn toàn, không có dòng log nào. Mọi chuỗi phải kết thúc bằng một bước xử lý lỗi hoặc một lần `join()`.
- **Đặt xử lý lỗi đúng tầng.** Demo gắn `exceptionally` vào riêng nhánh gợi ý, vì đó là phần tuỳ chọn. Hồ sơ user và
  đơn hàng là phần bắt buộc: lỗi ở đó phải làm cả trang lỗi.

`join()` hay `get()`: giống nhau, khác ở chỗ `get()` ném checked exception (`ExecutionException`,
`InterruptedException`) còn `join()` ném `CompletionException` không cần khai báo, tiện dùng trong lambda.

## 6. Timeout

```java
cf.orTimeout(2, TimeUnit.SECONDS);                        // quá hạn: kết thúc bằng TimeoutException
cf.completeOnTimeout("guest", 1, TimeUnit.SECONDS);       // quá hạn: kết thúc bằng giá trị mặc định
```

Mọi lời gọi ra ngoài đều cần timeout. Không có nó, một dịch vụ phía sau treo sẽ kéo treo cả chuỗi phía trước.

Giới hạn quan trọng: **timeout và `cancel()` không dừng công việc bên dưới**. CF chỉ được đánh dấu là đã kết thúc;
thread đang chạy lời gọi chậm kia vẫn chạy tiếp và vẫn chiếm chỗ trong pool. Timeout thật phải đặt ở tầng thực hiện
lời gọi: timeout của HTTP client, của JDBC.

## 7. Các bẫy

| Bẫy | Hậu quả | Cách tránh |
|-----|---------|-----------|
| `join()`/`get()` ngay sau mỗi `supplyAsync` | Thành tuần tự, tốn thêm thread | Tạo hết các CF trước, ghép lại, `join()` một lần ở cuối |
| Không chỉ định executor cho I/O | Cạn common pool, treo cả ứng dụng | Luôn truyền executor riêng |
| Chuỗi không có bước xử lý lỗi | Exception biến mất | `exceptionally`/`handle`, hoặc `join()` |
| `join()` bên trong một task của cùng pool | Pool tự kẹt (bài 5) | Dùng `thenCompose`/`thenCombine` thay vì chặn |
| `thenApply` trả về CF | `CF<CF<T>>` | `thenCompose` |
| Dựa vào `orTimeout` để giải phóng tài nguyên | Thread vẫn bị chiếm | Timeout ở tầng client |
| Dựa vào `ThreadLocal` (user hiện tại, transaction, MDC) trong callback | Callback chạy trên thread khác, không thấy giá trị | Truyền dữ liệu qua tham số; dùng executor có sao chép ngữ cảnh |

Về bẫy đầu tiên, cặp code sau trông gần giống nhau nhưng khác hẳn:

```java
// Tuần tự trá hình: join() thứ nhất chặn trước khi lời gọi thứ hai kịp bắt đầu
String u = CompletableFuture.supplyAsync(() -> fetchUser(), ioPool).join();
List<String> o = CompletableFuture.supplyAsync(() -> fetchOrders(), ioPool).join();

// Song song thật: cả hai đã chạy trước khi có lần chặn đầu tiên
CompletableFuture<String> uf = CompletableFuture.supplyAsync(() -> fetchUser(), ioPool);
CompletableFuture<List<String>> of = CompletableFuture.supplyAsync(() -> fetchOrders(), ioPool);
String u2 = uf.join();
List<String> o2 = of.join();
```

## 8. Khi nào dùng, khi nào không

| Tình huống | Gợi ý |
|-----------|-------|
| Vài lời gọi độc lập cần chạy song song rồi gộp | `CompletableFuture` |
| Chuỗi xử lý bất đồng bộ, không muốn giữ thread | `CompletableFuture` |
| Chỉ một việc chạy nền, không cần kết quả | `executor.execute(...)` |
| Rất nhiều việc chờ I/O, muốn code viết kiểu tuần tự | Virtual thread (bài 8): code chặn bình thường, dễ đọc và dễ debug hơn chuỗi callback |
| Tính toán trên tập dữ liệu lớn | Parallel stream / Fork/Join |

Từ JDK 21, với việc chờ I/O, virtual thread thường cho code đơn giản hơn `CompletableFuture` mà hiệu quả tương đương.
`CompletableFuture` vẫn đáng dùng khi cần ghép kết quả, và có ở mọi phiên bản từ Java 8.

## 9. Tự kiểm tra

1. Ba lời gọi 300, 400, 500 ms chạy song song bằng `thenCombine` mất khoảng bao lâu? Vì sao?
2. Khi nào dùng `thenCompose` thay cho `thenApply`?
3. `supplyAsync(() -> jdbcQuery())` không truyền executor. Có vấn đề gì?
4. `cf.orTimeout(1, SECONDS)` kích hoạt. Lời gọi HTTP bên trong có bị huỷ không?

Đáp án ngắn: (1) Khoảng 500 ms, bằng lời gọi chậm nhất. (2) Khi hàm trả về một `CompletableFuture` khác.
(3) Việc chặn chạy trên common pool dùng chung và rất nhỏ; vài truy vấn chậm làm treo các phần khác của ứng dụng.
(4) Không; chỉ CF được đánh dấu lỗi, thread kia vẫn chạy cho tới khi lời gọi tự xong hoặc tự timeout.

Bài tiếp theo: [07 — Synchronizer và phối hợp thread](07-synchronizer-va-phoi-hop-thread.md).
