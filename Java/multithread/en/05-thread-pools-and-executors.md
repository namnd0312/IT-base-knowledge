# 05 — Thread pools and executors

Goal: after this lesson you understand every parameter of `ThreadPoolExecutor`, can size a pool on reasoned grounds,
shut a pool down properly, and avoid two classic incidents: an unbounded queue and a pool stuck on itself.

## 1. Why not `new Thread` yourself

| One thread per job | Thread pool |
|--------------------|-------------|
| Pays creation and teardown for every job | Threads are reused |
| Under load the thread count grows without limit until memory runs out | The thread count has a ceiling |
| No place to queue, reject or observe | A queue, a rejection policy, metrics |
| Hard to shut down cleanly | `shutdown()` |

A thread pool separates **the work** (the task) from **how it runs** (which thread, when). You submit tasks; the pool decides.

## 2. `ExecutorService`

```java
ExecutorService pool = Executors.newFixedThreadPool(8);

pool.execute(() -> sendEmail());                    // Runnable, no result needed
Future<Integer> future = pool.submit(() -> 21 * 2); // Callable, with a result

try {
    Integer result = future.get(2, TimeUnit.SECONDS);   // blocks for at most 2 seconds
} catch (TimeoutException e) {
    future.cancel(true);                                // sends an interrupt to the task
} catch (ExecutionException e) {
    System.out.println("task failed: " + e.getCause()); // the real exception is in getCause()
}
```

| | `execute(Runnable)` | `submit(Callable/Runnable)` |
|---|---|---|
| Result | None | A `Future` |
| Exception in the task | Goes to the `UncaughtExceptionHandler`, printed to `stderr` | **Sits silently inside the `Future`** until `get()` is called |

The trap: `submit()` without ever calling `get()` means every task exception vanishes without a trace. Either use
`execute`, or always handle the `Future`, or wrap the task body in a logging `try/catch`.

`future.get()` without a timeout can block forever. In request-serving code, always use the timed variant.

Running several tasks and waiting for all of them:

```java
List<Callable<String>> calls = List.of(() -> "a", () -> "b");
for (Future<String> f : pool.invokeAll(calls)) {    // returns when every task is done
    System.out.println(f.get());
}
```

## 3. Inside `ThreadPoolExecutor`

The `Executors.newXxx` methods are just presets of one class: `ThreadPoolExecutor`. Understand its seven parameters
and you understand every kind of pool.

```java
AtomicInteger seq = new AtomicInteger();
ThreadPoolExecutor pool = new ThreadPoolExecutor(
        4,                                       // corePoolSize
        8,                                       // maximumPoolSize
        60, TimeUnit.SECONDS,                    // keepAliveTime for threads above core
        new ArrayBlockingQueue<>(100),           // a BOUNDED queue
        r -> new Thread(r, "report-worker-" + seq.incrementAndGet()),   // named threads
        new ThreadPoolExecutor.CallerRunsPolicy());                     // when overloaded
```

The processing order when a task is submitted, which most people get wrong:

```
new task
   │
   ▼
threads < corePoolSize ? ────yes──▶ create a new thread to run it
   │ no
   ▼
room in the queue ? ─────────yes──▶ enqueue it
   │ no (full)
   ▼
threads < maximumPoolSize ? ─yes──▶ create a new thread to run it
   │ no
   ▼
reject via the RejectedExecutionHandler
```

**The pool creates threads above core only when the queue is full.** The consequence: with an unbounded queue,
`maximumPoolSize` never has any effect; the pool stays at `corePoolSize` threads forever.

### The preset pools and their risks

| Factory | Real configuration | Risk |
|---------|--------------------|------|
| `newFixedThreadPool(n)` | core = max = n, an **unbounded** queue | Tasks pile up without limit when processing falls behind; out of memory |
| `newCachedThreadPool()` | core = 0, max = unlimited, no queue | The thread count grows without limit under load |
| `newSingleThreadExecutor()` | One thread, an unbounded queue | Same as fixed; guarantees sequential execution |
| `newScheduledThreadPool(n)` | Delayed or periodic execution | An unbounded queue |
| `newVirtualThreadPerTaskExecutor()` | One virtual thread per task | No concurrency limit (lesson 8) |

For a production service, declare a `ThreadPoolExecutor` yourself with a bounded queue and an explicit rejection policy.

### Policies when overloaded

| Handler | Behaviour | Use when |
|---------|-----------|----------|
| `AbortPolicy` (default) | Throws `RejectedExecutionException` | The caller should know and handle it (return 503) |
| `CallerRunsPolicy` | The submitting thread runs the task itself | You want natural backpressure: the submitter is slowed down |
| `DiscardPolicy` | Drops the task silently | Droppable work (metrics). Easily hides problems |
| `DiscardOldestPolicy` | Drops the longest-waiting task | Only the newest data matters |

Rejection is a feature: an overloaded system that still accepts everything collapses entirely; one that rejects some
keeps the rest alive.

## 4. Sizing a pool

| Kind of work | Formula | Example, 8-core machine |
|--------------|---------|-------------------------|
| Computation (CPU-bound) | number of cores, or cores + 1 | 8 |
| Waiting on I/O (I/O-bound) | cores × (1 + wait time / compute time) | Waiting 90 ms, computing 10 ms: 8 × 10 = 80 |

```java
int cores = Runtime.getRuntime().availableProcessors();
```

Notes:

- In a container, `availableProcessors()` returns the container's CPU limit, which may be 1 or 2.
- The formula is only a starting point. The real limit is usually further back: a 200-thread pool calling a database
  with 20 connections just has 180 threads queueing. Pool size should match the slowest resource the pool uses.
- Measure, then adjust. More threads than needed slow the system down through context switching and contention.

**Separate pools by kind of work.** Slow work (calls to an external service) sharing a pool with fast work takes all
the threads and brings the fast work down with it. Give each external dependency its own pool with its own limit (the
bulkhead pattern).

## 5. A pool stuck on itself (starvation deadlock)

Demo: [ThreadPoolStarvationDeadlockDemo.java](../demos/ThreadPoolStarvationDeadlockDemo.java). A 2-thread pool runs 2 orders;
each order submits a payment subtask to **that same pool** and waits for the result.

```java
static String processOrder(int orderId, ExecutorService paymentPool) throws Exception {
    Future<String> payment = paymentPool.submit(() -> {
        Thread.sleep(200);
        return "paid";
    });
    return "order " + orderId + " -> " + payment.get();    // blocks a pool thread
}
```

```
PID=52329 mode=broken
orderPool state: java.util.concurrent.ThreadPoolExecutor@330bedb4[Running, pool size = 2, active threads = 2, queued tasks = 2, completed tasks = 0]
RESULT: WRONG (no order finished in time: every pool thread waits for a queued subtask)
```

Read the state line: both threads are busy (`active threads = 2`), 2 subtasks sit in the queue (`queued tasks = 2`),
nothing has completed. The two busy threads are exactly the two threads waiting for those two subtasks. Nobody is left
to run the subtasks.

```
Thread 1: runs order 1 ── waits for payment 1 ──┐
Thread 2: runs order 2 ── waits for payment 2 ──┤   both sit in the queue,
Queue:    [payment 1, payment 2] ◀──────────────┘   no free thread remains
```

No lock is involved, so `jstack` **does not report a deadlock**. A thread dump only shows every pool thread `WAITING`
in `FutureTask.get` (lesson 9).

In practice this bug only appears when load is high enough for every thread to be busy at once, in other words at the
worst possible moment.

How to fix it:

| Approach | Notes |
|----------|-------|
| Run subtasks in a different pool | The demo's fix |
| Do not block: compose with `CompletableFuture` | The submitting thread is returned to the pool immediately (lesson 6) |
| Run the subtask on the current thread | When parallelism is not really needed |

```
mode=fixed
order 1 -> paid
order 2 -> paid
RESULT: CORRECT (all orders completed)
```

The rule: **a task in a pool never blocks waiting for another task of the same pool.**

## 6. Shutting a pool down

Pool threads are user threads: without shutting the pool down, the JVM does not exit.

```java
pool.shutdown();                                        // no new tasks; finishes those already accepted
if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
    pool.shutdownNow();                                 // sends an interrupt to running tasks
}
```

| Method | Running tasks | Queued tasks |
|--------|---------------|--------------|
| `shutdown()` | Continue | Still run |
| `shutdownNow()` | Are interrupted | Are dropped and returned as a list |
| `close()` (JDK 19+, for try-with-resources) | Wait until done | Still run |

`shutdownNow()` only **sends an interrupt**. A task that swallows `InterruptedException` or never checks the flag will
not stop (lesson 1).

In Spring, declare the pool as a bean (`ThreadPoolTaskExecutor`) so the framework shuts it down when the application stops.

## 7. Scheduled execution

```java
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

scheduler.schedule(() -> System.out.println("once, after 100 ms"), 100, TimeUnit.MILLISECONDS);

ScheduledFuture<?> heartbeat = scheduler.scheduleAtFixedRate(
        () -> System.out.println("tick"), 0, 50, TimeUnit.MILLISECONDS);

heartbeat.cancel(false);
scheduler.shutdown();
```

| Method | The interval is measured from |
|--------|-------------------------------|
| `scheduleAtFixedRate` | The **start** of the previous run. If a run takes longer than the period, the next starts as soon as it ends; runs never overlap |
| `scheduleWithFixedDelay` | The **end** of the previous run |

A serious trap: if a periodic task throws an exception, **every later run is cancelled silently**. Always wrap the
whole task body in a logging `try/catch`.

## 8. Fork/Join and parallel streams

`ForkJoinPool` is for **divide-and-conquer computation**: split the problem in two, solve the halves in parallel,
combine the results. Each thread has its own queue and "steals" work from others when idle.

```java
static class SumTask extends RecursiveTask<Long> {
    ...
    @Override protected Long compute() {
        if (to - from <= 10_000) { /* small enough: compute directly */ }
        int mid = (from + to) / 2;
        SumTask left = new SumTask(data, from, mid);
        left.fork();                                        // the left half runs asynchronously
        long right = new SumTask(data, mid, to).compute();  // the right half runs right here
        return left.join() + right;
    }
}

long sum = ForkJoinPool.commonPool().invoke(new SumTask(data, 0, data.length));
long sum2 = Arrays.stream(data).parallel().sum();           // parallel streams use the common pool
```

The common pool is shared across the whole JVM and by default has (cores − 1) threads: on an 8-core machine the
measured value was `parallelism=7`.

- Parallel streams are only worth it for **computation over large data**. For a few hundred elements they are slower
  than sequential.
- Do **not** run blocking work (I/O) in a parallel stream or the common pool: you take the threads of every other
  library in the JVM, including any `CompletableFuture` without an explicit executor.

## 9. Self-check

1. A pool has core = 4, max = 16 and an unbounded `LinkedBlockingQueue`. How many threads does it have under heavy load?
2. A task is submitted with `submit()`, it throws, and nobody calls `get()`. What shows up in the log?
3. Why does `jstack` not report a pool stuck on itself as a deadlock?
4. A periodic task that ran fine suddenly stops for good, with no error log. The most common cause?

Short answers: (1) 4: threads above core are only created when the queue is full, and this queue never is.
(2) Nothing; the exception is inside the `Future`. (3) There is no cycle of lock waits; the threads are just waiting
for a `Future`. (4) One run threw an exception, and `ScheduledExecutorService` cancelled every later run.

Next: [06 — CompletableFuture](06-completablefuture.md).
