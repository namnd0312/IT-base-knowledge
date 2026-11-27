# 08 — Virtual threads

Goal: after this lesson you understand how virtual threads (final since JDK 21) differ from platform threads, know
when they help and when they do not, and avoid the mistakes of migrating old code to them.

## 1. The problem they solve

The traditional Java server model: **one thread per request**. The code is easy to write, read and debug. But:

- Every platform thread is an operating-system thread, costing about 1 MB of stack plus kernel scheduling overhead.
- A machine can only handle a few thousand threads, while request threads spend most of their time **waiting** for a
  database or another service.

The result: the machine has CPU to spare but runs out of threads. The earlier cure was asynchronous code
(`CompletableFuture`, reactive): efficient, but hard to read, with meaningless stack traces, and hard to debug.

Virtual threads keep the "one thread per request, written sequentially, just block" style and remove the thread-count limit.

## 2. How they work

```
   Virtual threads (hundreds of thousands, managed by the JVM, very light)
   ┌──┐┌──┐┌──┐┌──┐┌──┐┌──┐┌──┐┌──┐┌──┐┌──┐ ...
   └┬─┘└──┘└┬─┘└──┘└──┘└┬─┘└──┘└──┘└──┘└──┘
    │ mount │           │         the rest are waiting on I/O: parked on the heap,
    ▼       ▼           ▼         occupying no thread at all
   ┌────┐ ┌────┐     ┌────┐
   │ C1 │ │ C2 │ ... │ Cn │   Carrier threads = platform threads, about one per core
   └────┘ └────┘     └────┘
```

- A virtual thread runs **on** a carrier thread (a platform thread of a dedicated JVM `ForkJoinPool`).
- When a virtual thread hits a blocking operation (`sleep`, a socket read, `lock`, `BlockingQueue.take`...), the JVM
  **unmounts** it from the carrier, stores its stack on the heap, and the carrier goes on to run another virtual thread.
- When the operation completes, the virtual thread is mounted on any carrier and continues.

To your code it is still an ordinary `Thread`: `Thread.currentThread()` works, stack traces are complete, and you can
step through it in a debugger.

| | Platform thread | Virtual thread |
|---|---|---|
| Mapping | 1 : 1 with an operating-system thread | Very many : few carriers |
| Memory | About 1 MB of stack, reserved up front | A few KB, growing on demand, on the heap |
| Sensible count | Thousands | Hundreds of thousands to millions |
| Creation | Expensive: pool them | Very cheap: one per task, **never pooled** |
| When blocking | Occupies an operating-system thread | Releases the carrier |
| Daemon | Optional | Always daemon |

## 3. Demo

[VirtualThreadsBlockingIoDemo.java](../demos/VirtualThreadsBlockingIoDemo.java): 2000 tasks, each blocking for 200 ms
(simulating a database call).

```java
try (ExecutorService executor = virtual
        ? Executors.newVirtualThreadPerTaskExecutor()
        : Executors.newFixedThreadPool(PLATFORM_POOL_SIZE)) {
    for (int i = 0; i < TASKS; i++) {
        executor.execute(() -> {
            Thread.sleep(BLOCK_MILLIS);     // a blocking call
            completed.incrementAndGet();
        });
    }
}
```

```
mode=platform(pool=100) tasks=2,000 blockMillis=200
completed=2,000 elapsed=4075 ms

mode=virtual tasks=2,000 blockMillis=200
completed=2,000 elapsed=230 ms
```

A 100-thread pool runs only 100 tasks per wave: 20 waves × 200 ms = 4 seconds. With virtual threads all 2000 tasks
"sleep" at once on a handful of carriers and finish after a little over 200 ms.

Read the result carefully: no task ran faster. Each still takes 200 ms. What grew is **how many tasks can wait at the same time**.

## 4. Usage

```java
Thread v1 = Thread.ofVirtual().name("vt-1").start(() -> System.out.println(Thread.currentThread()));
Thread v2 = Thread.startVirtualThread(() -> { });

try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    Future<String> f = executor.submit(() -> "done on " + Thread.currentThread());
    System.out.println(f.get());
}
```

```
VirtualThread[#47,vt-1]/runnable@ForkJoinPool-1-worker-1
true daemon=true
done on VirtualThread[#52]/runnable@ForkJoinPool-1-worker-3
```

The part after `@` is the carrier it is mounted on: `ForkJoinPool-1-worker-1`.

In Spring Boot 3.2 and later one configuration line makes every request run on a virtual thread:

```yaml
spring:
  threads:
    virtual:
      enabled: true
```

## 5. When they help, when they do not

| Kind of work | Virtual threads | Reason |
|--------------|-----------------|--------|
| Waiting on I/O: database, HTTP, queues | **A great fit** | Blocking releases the carrier |
| Very many concurrent connections, each mostly waiting | **A great fit** | No longer limited by the thread count |
| Heavy computation (CPU-bound) | No help | There are still only so many cores; computation does not release the carrier |
| Work needing maximum CPU throughput | Use a platform pool sized to the cores | As in lesson 5 |

Virtual threads do not make code faster. They make **waiting** cheap.

## 6. What requires a change of thinking

### Do not pool virtual threads

Pools exist because threads are expensive. Virtual threads are cheap, so create one per task and discard it. Putting
virtual threads in a fixed-size pool reattaches exactly the limit you just removed.

### Limit concurrency with a `Semaphore`

Previously the pool size acted as an accidental limit: a 50-thread pool meant at most 50 queries at once. With virtual
threads, 10,000 requests become 10,000 concurrent queries hitting a database that has 20 connections.

The limit must be stated explicitly (lesson 7):

```java
Semaphore dbPermits = new Semaphore(20);

dbPermits.acquire();
try {
    // call the database
} finally {
    dbPermits.release();
}
```

A database connection pool, if present, still plays this role. What needs reviewing is every resource that used to be
protected **only** by the thread-pool size.

### Pinning

In some cases a blocked virtual thread **cannot** release its carrier; it is "pinned". The carrier is occupied for the
whole blocking time, and since there are only about as many carriers as cores, a few pinned virtual threads are enough
to choke everything.

| Cause | JDK 21 to 23 | JDK 24 and later |
|-------|--------------|------------------|
| Blocking inside a `synchronized` block | **Pinned** | No longer (JEP 491) |
| Blocking inside a native method (JNI) | Pinned | Still pinned |

On JDK 21, for code that holds a lock while doing I/O, replace `synchronized` with `ReentrantLock`:

```java
private final ReentrantLock lock = new ReentrantLock();

lock.lock();
try {
    callRemote();        // blocking while holding the lock: with ReentrantLock the carrier is still released
} finally {
    lock.unlock();
}
```

A `synchronized` that only wraps a short in-memory computation needs no change. Find pinning on JDK 21 with
`-Djdk.tracePinnedThreads=full`, or the `jdk.VirtualThreadPinned` event in JFR. Older libraries (JDBC drivers, HTTP
clients) may pin internally; use recent versions.

### `ThreadLocal`

It still works; every virtual thread has its own copy. But two old habits become problems:

- **Using `ThreadLocal` as a cache for expensive objects** (buffers, formatters) on the assumption that threads are
  reused: virtual threads are not reused, so the cache never hits, and a million threads means a million copies.
- Passing context through `ThreadLocal` remains correct. `ScopedValue` (preview in JDK 21, final in JDK 25) is the
  replacement designed for virtual threads.

## 7. Structured concurrency

`StructuredTaskScope` treats a group of parallel subtasks as one unit: the group ends together, one failure cancels
the rest, and thread dumps show the parent–child relationship. It solves exactly the problem of demo 6 with
blocking-style code instead of a callback chain.

The API is still in **preview** from JDK 21 through JDK 25 and has changed shape between versions. This guide
therefore gives no sample code; when you use it, read the JEP matching your exact JDK version and enable `--enable-preview`.

## 8. Observing and debugging

- `jstack` and `jcmd <pid> Thread.print` list platform threads only (carriers appear as `ForkJoinPool-1-worker-N`).
- A dump including virtual threads: `jcmd <pid> Thread.dump_to_file -format=json <file>`.
- Since there may be hundreds of thousands of virtual threads, give them meaningful names and rely on logs and metrics
  rather than reading dumps by eye.

## 9. Migration checklist

- [ ] The workload is mostly waiting on I/O, not computation.
- [ ] Virtual threads are not pooled; `newVirtualThreadPerTaskExecutor()` is used.
- [ ] Every resource with finite capacity has an explicit limit (`Semaphore`, connection pool).
- [ ] On JDK 21–23: no `synchronized` around I/O on hot paths; a trial run with `jdk.tracePinnedThreads` was done.
- [ ] `ThreadLocal` is not used as a per-thread cache.
- [ ] Drivers and libraries are at versions that support virtual threads.
- [ ] Measured under real load before and after enabling.

## 10. Self-check

1. Does an image-compression service (CPU-bound) get faster on virtual threads?
2. Why should you not create a pool of virtual threads?
3. Before migrating, a 50-thread pool called an API limited to 50 connections. What must be added afterwards?
4. On JDK 21, what problem does a `synchronized` method containing a JDBC call cause with virtual threads?

Short answers: (1) No; the core count is unchanged and computation does not release the carrier. (2) Pools exist to
reuse expensive threads; virtual threads are cheap, and a pool only reinstates the old limit. (3) A `Semaphore(50)`
around the call, because the implicit limit of the pool size is gone. (4) Pinning: the virtual thread holds its
carrier for the whole JDBC call; a few of them use up all carriers.

Next: [09 — Debugging, thread dumps and troubleshooting](09-debugging-thread-dumps-and-troubleshooting.md).
