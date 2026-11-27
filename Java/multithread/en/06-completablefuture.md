# 06 — CompletableFuture

Goal: after this lesson you can compose several asynchronous tasks without blocking a thread, handle errors and
timeouts properly, and know the `CompletableFuture` traps before meeting them in production.

## 1. Why `Future` is not enough

`Future` (lesson 5) has one way to get the result: `get()`, and it **blocks**. "When done, do the next thing", "wait
for all three and combine them", "on failure use a fallback" all have to be hand-written, with a thread sitting idle.

`CompletableFuture` (CF) lets you **describe the whole pipeline up front** and have it run by itself as data becomes available.

| | `Future` | `CompletableFuture` |
|---|---|---|
| Getting the result | Blocking `get()` only | Attach callbacks, or `join()` at the end |
| Chaining the next step | No | `thenApply`, `thenCompose`... |
| Combining results | Hand-written | `thenCombine`, `allOf`, `anyOf` |
| Error handling | `try/catch` around `get()` | `exceptionally`, `handle` |
| Timeout | `get(timeout)` | `orTimeout`, `completeOnTimeout` |

## 2. Demo: sequential and parallel

Demo: [CompletableFutureCompositionDemo.java](../demos/CompletableFutureCompositionDemo.java). A dashboard needs three
independent things: the user profile (300 ms), the orders (400 ms), the recommendations (500 ms).

Sequential, each call waits for the previous one:

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

Parallel, all three start at once:

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

1212 ms down to 510 ms: the total equals the **slowest** call instead of the **sum** of the calls.

When the recommendation service is down, the page still renders with fallback data:

```
mode=failure
recommendations failed: java.lang.IllegalStateException: recommendation service is down -> using fallback
Dashboard[user=an, orders=[order-1, order-2], recommendations=[bestseller-1]]
elapsed=519 ms
```

## 3. The vocabulary

```java
CompletableFuture<String> user = CompletableFuture.supplyAsync(() -> findUser(1), ioPool);

CompletableFuture<String> upper = user.thenApply(String::toUpperCase);                   // transform
CompletableFuture<List<String>> orders = user.thenCompose(Snippets::findOrdersAsync);    // chain an async step
CompletableFuture<String> summary =
        user.thenCombine(orders, (u, o) -> u + " has " + o.size() + " orders");          // combine two results
CompletableFuture<Void> all = CompletableFuture.allOf(upper, orders, summary);           // wait for all
CompletableFuture<Object> first = CompletableFuture.anyOf(upper, summary);               // whichever finishes first
```

| Method | Takes | Returns | Equivalent |
|--------|-------|---------|------------|
| `supplyAsync(supplier, executor)` | Work with a result | `CF<T>` | Starts a chain |
| `runAsync(runnable, executor)` | Work without a result | `CF<Void>` | Starts a chain |
| `thenApply(fn)` | `T -> U` | `CF<U>` | `map` |
| `thenCompose(fn)` | `T -> CF<U>` | `CF<U>` | `flatMap` |
| `thenAccept(consumer)` | `T -> void` | `CF<Void>` | Final step: consume the result |
| `thenCombine(other, fn)` | Two independent CFs | `CF<V>` | Join two parallel branches |
| `allOf(cf...)` | Several CFs | `CF<Void>` | Wait for all; read each result with `join()` |
| `anyOf(cf...)` | Several CFs | `CF<Object>` | The first to complete |

`thenApply` or `thenCompose`: if your function returns a plain value, use `thenApply`. If it returns another
`CompletableFuture` (it starts further asynchronous work), use `thenCompose`; using `thenApply` by mistake yields a `CF<CF<U>>`.

**Independent work: `thenCombine`/`allOf` (parallel); dependent work: `thenCompose` (sequential).**

## 4. Which thread runs a callback

| Form | The callback runs on |
|------|----------------------|
| `thenApply(fn)` | The thread that completed the previous stage; or **the thread calling `thenApply`** if that stage is already complete |
| `thenApplyAsync(fn)` | `ForkJoinPool.commonPool()` |
| `thenApplyAsync(fn, executor)` | The executor you specify |

The consequence: a callback without the `Async` suffix may run on a thread you did not expect (an HTTP client's I/O
thread, the caller's thread). For heavy or blocking callbacks use the `Async` variant with an explicit executor:

```java
CompletableFuture<Void> logged = summary.thenAcceptAsync(System.out::println, ioPool);
```

### Always pass an executor for blocking work

`supplyAsync(supplier)` without an executor runs on the **common pool**: shared by the whole JVM, with only
(cores − 1) threads, designed for computation. Put database or HTTP calls there and a few slow requests take the whole
pool, stalling every parallel stream and every other `CompletableFuture` in the application.

```java
CompletableFuture.supplyAsync(() -> callRemote());            // WRONG for I/O: uses the common pool
CompletableFuture.supplyAsync(() -> callRemote(), ioPool);    // RIGHT
```

## 5. Error handling

An exception at any stage makes the later `then...` stages skip, and the CF ends in a failed state, until an
error-handling stage is reached. Think of a `try/catch` stretched along the chain.

```java
CompletableFuture<String> safe = summary
        .orTimeout(2, TimeUnit.SECONDS)
        .exceptionally(error -> "summary unavailable");                       // runs on failure only

CompletableFuture<String> handled =
        summary.handle((value, error) -> error == null ? value : "fallback"); // always runs
```

| Method | Runs when | Use to |
|--------|-----------|--------|
| `exceptionally(fn)` | There is an error | Return a fallback value |
| `handle((value, error) -> ...)` | Always | Turn both result and error into one value |
| `whenComplete((value, error) -> ...)` | Always | Side effects (logging, metrics); does not change the result |

Three things to know:

- **Exceptions are wrapped.** The original error sits inside a `CompletionException`. In the demo output,
  `error.getMessage()` prints `java.lang.IllegalStateException: recommendation service is down`: the class name comes
  first because that is the wrapper's message. Use `error.getCause()` for the original.
- **Unhandled errors are silent.** A CF on which nobody calls `join()`/`get()` and which has no
  `exceptionally`/`handle` swallows the exception completely, with no log line at all. Every chain must end with an
  error-handling stage or a `join()`.
- **Handle errors at the right level.** The demo attaches `exceptionally` to the recommendation branch only, because
  that part is optional. The user profile and the orders are mandatory: a failure there must fail the whole page.

`join()` or `get()`: the same, except `get()` throws checked exceptions (`ExecutionException`, `InterruptedException`)
while `join()` throws the unchecked `CompletionException`, which is convenient inside lambdas.

## 6. Timeouts

```java
cf.orTimeout(2, TimeUnit.SECONDS);                        // on expiry: completes with a TimeoutException
cf.completeOnTimeout("guest", 1, TimeUnit.SECONDS);       // on expiry: completes with a default value
```

Every outbound call needs a timeout. Without one, a hung downstream service hangs the whole chain in front of it.

An important limit: **a timeout or `cancel()` does not stop the underlying work**. The CF is merely marked as
completed; the thread running the slow call keeps going and still occupies a pool slot. The real timeout must be set
where the call is made: on the HTTP client, on JDBC.

## 7. The traps

| Trap | Consequence | How to avoid |
|------|-------------|--------------|
| `join()`/`get()` right after each `supplyAsync` | Becomes sequential and wastes threads | Create all CFs first, compose them, `join()` once at the end |
| No executor for I/O | Exhausts the common pool, stalls the application | Always pass a dedicated executor |
| A chain without an error-handling stage | Exceptions vanish | `exceptionally`/`handle`, or `join()` |
| `join()` inside a task of the same pool | The pool gets stuck on itself (lesson 5) | Use `thenCompose`/`thenCombine` instead of blocking |
| `thenApply` returning a CF | `CF<CF<T>>` | `thenCompose` |
| Relying on `orTimeout` to free resources | The thread stays occupied | Timeouts at the client level |
| Relying on `ThreadLocal` (current user, transaction, MDC) in callbacks | The callback runs on another thread and does not see the value | Pass data as parameters; use a context-propagating executor |

On the first trap, the following two snippets look almost alike but differ completely:

```java
// Sequential in disguise: the first join() blocks before the second call even starts
String u = CompletableFuture.supplyAsync(() -> fetchUser(), ioPool).join();
List<String> o = CompletableFuture.supplyAsync(() -> fetchOrders(), ioPool).join();

// Truly parallel: both are running before the first block
CompletableFuture<String> uf = CompletableFuture.supplyAsync(() -> fetchUser(), ioPool);
CompletableFuture<List<String>> of = CompletableFuture.supplyAsync(() -> fetchOrders(), ioPool);
String u2 = uf.join();
List<String> o2 = of.join();
```

## 8. When to use it, when not

| Situation | Suggestion |
|-----------|------------|
| A few independent calls to run in parallel and combine | `CompletableFuture` |
| An asynchronous pipeline that should not hold a thread | `CompletableFuture` |
| One background job, no result needed | `executor.execute(...)` |
| Very many I/O-bound jobs, and you want sequential-looking code | Virtual threads (lesson 8): plain blocking code, easier to read and debug than callback chains |
| Computation over a large data set | Parallel streams / Fork/Join |

Since JDK 21, for I/O-bound work virtual threads usually give simpler code than `CompletableFuture` with comparable
efficiency. `CompletableFuture` remains worthwhile for combining results, and is available in every version since Java 8.

## 9. Self-check

1. Three calls of 300, 400 and 500 ms run in parallel with `thenCombine`: roughly how long? Why?
2. When do you use `thenCompose` instead of `thenApply`?
3. `supplyAsync(() -> jdbcQuery())` with no executor. What is the problem?
4. `cf.orTimeout(1, SECONDS)` fires. Is the HTTP call inside cancelled?

Short answers: (1) About 500 ms, the slowest call. (2) When the function returns another `CompletableFuture`.
(3) Blocking work runs on the shared and very small common pool; a few slow queries stall other parts of the
application. (4) No; only the CF is marked failed, the other thread keeps running until the call finishes or times out by itself.

Next: [07 — Synchronizers and thread coordination](07-synchronizers-and-thread-coordination.md).
