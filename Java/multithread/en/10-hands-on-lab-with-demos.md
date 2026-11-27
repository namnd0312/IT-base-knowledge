# 10 — Hands-on lab: find and fix across 8 demos

Every demo follows the same loop: **run the broken version → observe → read the code to find the cause → run the fix → verify**.
Sample output comes from JDK 21.0.5 on an 8-core machine. Your numbers will differ; the race-condition demos even give
different numbers on **every run**, which is part of the lesson.

## Setup

```bash
cd Java/multithread
javac -d out demos/*.java
```

Some demos need two terminals: **T1** runs the demo, **T2** takes thread dumps. Find the PID from the `PID=...` line a
demo prints, or with `jcmd -l`.

| # | Demo | Modes | Theory lesson |
|---|------|-------|---------------|
| 1 | `RaceConditionCounterDemo` | `broken` / `fixed` | [02](02-race-conditions-visibility-and-the-java-memory-model.md) |
| 2 | `VisibilityStopFlagDemo` | `broken` / `fixed` | [02](02-race-conditions-visibility-and-the-java-memory-model.md) |
| 3 | `DeadlockTransferDemo` | `broken` / `fixed` | [03](03-synchronized-locks-and-deadlock.md) |
| 4 | `CheckThenActConcurrentMapDemo` | `broken` / `fixed` | [04](04-atomics-concurrent-collections-and-thread-safety-strategies.md) |
| 5 | `ThreadPoolStarvationDeadlockDemo` | `broken` / `fixed` | [05](05-thread-pools-and-executors.md) |
| 6 | `CompletableFutureCompositionDemo` | `sequential` / `parallel` / `failure` | [06](06-completablefuture.md) |
| 7 | `SemaphoreConcurrencyLimitDemo` | `broken` / `fixed` | [07](07-synchronizers-and-thread-coordination.md) |
| 8 | `VirtualThreadsBlockingIoDemo` | `platform` / `virtual` | [08](08-virtual-threads.md) |

---

## Demo 1 — Race condition: lost updates

File: [RaceConditionCounterDemo.java](../demos/RaceConditionCounterDemo.java)

### Run the broken version

```bash
java -cp out RaceConditionCounterDemo broken
java -cp out RaceConditionCounterDemo broken
```

```
mode=broken threads=8 incrementsPerThread=1,000,000
expected=8,000,000 actual=1,626,740 lost=6,373,260
RESULT: WRONG (lost updates)

mode=broken threads=8 incrementsPerThread=1,000,000
expected=8,000,000 actual=1,963,442 lost=6,036,558
RESULT: WRONG (lost updates)
```

Observe: no exception. The result is wrong, and wrong **differently** each time. That is the signature of a race condition.

### Find the cause

```java
static int plainCounter = 0;
...
plainCounter++;
```

Ask the three questions of lesson 2: is the variable shared (yes, `static`), is it mutable (yes), does a thread write
it (all 8). Nothing protects it. `++` is read, add, write; when two threads read the same value, one addition is lost.

### Run the fix

```bash
java -cp out RaceConditionCounterDemo fixed
```

```
expected=8,000,000 actual=8,000,000 lost=0
RESULT: CORRECT
```

`AtomicInteger.incrementAndGet()` performs all three steps as one atomic operation.

### Try more

- Change `static int plainCounter` to `static volatile int plainCounter` and run `broken`. Still wrong: `volatile`
  does not make `++` atomic.
- Change `THREADS` to 1. The broken version gives the right answer: without sharing there is no bug.

---

## Demo 2 — Visibility: a thread does not see the stop flag

File: [VisibilityStopFlagDemo.java](../demos/VisibilityStopFlagDemo.java)

### Run the broken version

```bash
java -cp out VisibilityStopFlagDemo broken
```

```
mode=broken
main: set running=false
RESULT: WRONG (worker is still spinning 3s after the flag changed)
```

`main` set the flag to `false`, but the `worker: saw the stop flag...` line never appears.

### Trace (T2, during the 3 seconds the demo is still running)

```bash
jstack $(jcmd -l | grep VisibilityStopFlagDemo | cut -d' ' -f1) | grep -A 3 '"spinner"'
```

```
"spinner" #20 [28931] daemon prio=5 os_prio=31 cpu=2169.71ms elapsed=2.17s ... runnable
   java.lang.Thread.State: RUNNABLE
	at VisibilityStopFlagDemo.lambda$main$0(VisibilityStopFlagDemo.java:33)
```

`RUNNABLE`, `cpu` about equal to `elapsed`: the worker spins at full speed and never re-reads the flag.

### Prove the JIT is responsible

```bash
java -Xint -cp out VisibilityStopFlagDemo broken
```

```
mode=broken
main: set running=false
worker: saw the stop flag after 197,008,279 iterations
RESULT: CORRECT (worker stopped)
```

`-Xint` turns the JIT off; the loop runs interpreted and re-reads the variable on every iteration. The same class
file, the same bug, and this time it is "correct". The lesson: wrong concurrent code can run correctly in tests, in a
debugger, on your machine, and then fail in production.

### Run the fix

```bash
java -cp out VisibilityStopFlagDemo fixed
```

```
mode=fixed
main: set running=false
worker: saw the stop flag after 1,753,059,506 iterations
RESULT: CORRECT (worker stopped)
```

The only difference in the code is the `volatile` keyword.

---

## Demo 3 — Deadlock

File: [DeadlockTransferDemo.java](../demos/DeadlockTransferDemo.java)

### Run the broken version

```bash
java -cp out DeadlockTransferDemo broken
```

```
PID=52110 mode=broken
DEADLOCK detected by ThreadMXBean:
  "transfer-A-to-B" BLOCKED, waiting for lock held by "transfer-B-to-A"
  "transfer-B-to-A" BLOCKED, waiting for lock held by "transfer-A-to-B"
RESULT: WRONG (deadlock, transfers never finish)
```

The demo detects it with `ThreadMXBean.findDeadlockedThreads()` and exits. Both threads are daemons, so the JVM can exit.

### Trace with jstack

T1, keeping the process alive for 60 seconds:

```bash
java -DhangSeconds=60 -cp out DeadlockTransferDemo broken
```

T2:

```bash
jstack <pid> | sed -n '/Found one Java-level deadlock/,$p'
```

```
Found one Java-level deadlock:
=============================
"transfer-A-to-B":
  waiting to lock monitor 0x0000600001e4c000 (object 0x000000070fc2ce78, a DeadlockTransferDemo$Account),
  which is held by "transfer-B-to-A"

"transfer-B-to-A":
  waiting to lock monitor 0x0000600001e48270 (object 0x000000070fc2ce60, a DeadlockTransferDemo$Account),
  which is held by "transfer-A-to-B"
...
"transfer-A-to-B":
	at DeadlockTransferDemo.transferBroken(DeadlockTransferDemo.java:35)
	- waiting to lock <0x000000070fc2ce78> (a DeadlockTransferDemo$Account)
	- locked <0x000000070fc2ce60> (a DeadlockTransferDemo$Account)
...
Found 1 deadlock.
```

The dump points straight at line 35 of `transferBroken`: the inner `synchronized (to)` block.

### Find the cause

The lock order depends on the argument order: `transfer(A, B)` locks A then B, `transfer(B, A)` locks B then A.
The `Thread.sleep(100)` between the two locks only makes the deadlock happen on every run; remove it and the deadlock
can still happen, just rarely, which is the worst kind of bug.

### Run the fix

```bash
java -cp out DeadlockTransferDemo fixed
```

```
PID=52113 mode=fixed
balances: A=1100 B=900 total=2000
RESULT: CORRECT (both transfers finished)
```

Always lock the account with the smaller `id` first. The total balance is still 2000: the invariant holds.

---

## Demo 4 — Check-then-act on a ConcurrentHashMap

File: [CheckThenActConcurrentMapDemo.java](../demos/CheckThenActConcurrentMapDemo.java)

```bash
java -cp out CheckThenActConcurrentMapDemo broken
java -cp out CheckThenActConcurrentMapDemo fixed
```

```
mode=broken threads=8 hitsPerThread=200,000
expected=1,600,000 actual=231,637 lost=1,368,363
RESULT: WRONG (lost updates)

mode=fixed threads=8 hitsPerThread=200,000
expected=1,600,000 actual=1,600,000 lost=0
RESULT: CORRECT
```

Observe: the broken version uses `ConcurrentHashMap`, a thread-safe class, and still loses more than 85% of the hits.

The cause: `get` then `put` are two calls. Each call is safe; the gap between them is not.
The fix: `hits.merge(PAGE, 1, Integer::sum)` does the read, the addition and the write in one atomic operation.

Compare with demo 1: the same kind of bug (read-modify-write), only hidden behind an API that looks safe.

---

## Demo 5 — A pool stuck on itself

File: [ThreadPoolStarvationDeadlockDemo.java](../demos/ThreadPoolStarvationDeadlockDemo.java)

### Run the broken version

```bash
java -cp out ThreadPoolStarvationDeadlockDemo broken
```

```
PID=52329 mode=broken
orderPool state: java.util.concurrent.ThreadPoolExecutor@330bedb4[Running, pool size = 2, active threads = 2, queued tasks = 2, completed tasks = 0]
RESULT: WRONG (no order finished in time: every pool thread waits for a queued subtask)
```

### Trace

T1: `java -DwaitSeconds=60 -cp out ThreadPoolStarvationDeadlockDemo broken`

T2:

```bash
jstack <pid> | grep -c 'deadlock'
jstack <pid> | sed -n '/^"pool-1-thread-1"/,/^$/p'
```

```
0
"pool-1-thread-1" #20 [29955] prio=5 os_prio=31 cpu=0.35ms elapsed=2.17s ... waiting on condition
   java.lang.Thread.State: WAITING (parking)
	- parking to wait for  <0x000000070fc02b50> (a java.util.concurrent.FutureTask)
	at java.util.concurrent.FutureTask.get(java.base@21.0.5/FutureTask.java:190)
	at ThreadPoolStarvationDeadlockDemo.processOrder(ThreadPoolStarvationDeadlockDemo.java:54)
	...
	at java.util.concurrent.ThreadPoolExecutor.runWorker(java.base@21.0.5/ThreadPoolExecutor.java:1144)
```

`jstack` reports **0 deadlocks**. Compare with demo 3: there the JVM points out the lock cycle by itself; here you
must recognise the pattern "a pool thread waiting for a `FutureTask`", together with `active threads = pool size` and
`queued tasks > 0`.

### Run the fix

```bash
java -cp out ThreadPoolStarvationDeadlockDemo fixed
```

```
PID=... mode=fixed
order 1 -> paid
order 2 -> paid
RESULT: CORRECT (all orders completed)
```

The payment subtasks run in their own pool.

### Try more

In the broken version, change the `newFixedThreadPool(2)` of `orderPool` to `newFixedThreadPool(3)`. The demo passes.
The bug is not gone: it is only waiting for 3 simultaneous orders. This is why this kind of bug only appears under load.

---

## Demo 6 — CompletableFuture

File: [CompletableFutureCompositionDemo.java](../demos/CompletableFutureCompositionDemo.java)

```bash
java -cp out CompletableFutureCompositionDemo sequential
java -cp out CompletableFutureCompositionDemo parallel
java -cp out CompletableFutureCompositionDemo failure
```

```
mode=sequential
Dashboard[user=an, orders=[order-1, order-2], recommendations=[book-7, book-9]]
elapsed=1212 ms

mode=parallel
Dashboard[user=an, orders=[order-1, order-2], recommendations=[book-7, book-9]]
elapsed=510 ms

mode=failure
recommendations failed: java.lang.IllegalStateException: recommendation service is down -> using fallback
Dashboard[user=an, orders=[order-1, order-2], recommendations=[bestseller-1]]
elapsed=519 ms
```

Reading the results:

- `sequential`: 300 + 400 + 500 = 1200 ms.
- `parallel`: equal to the slowest call, 500 ms. The result is identical.
- `failure`: the recommendation service fails, and the page still returns in 519 ms with the fallback list.

### Try more

- In `loadParallel`, add `.join()` right after the first `supplyAsync` (change the type of `user` to `String` and wrap
  it again with `CompletableFuture.completedFuture`). The time goes back to about 800 ms: you just turned parallel into sequential.
- Remove the `.exceptionally(...)` block and run `failure`. The whole page fails with a `CompletionException`.
- Change `ioPool` to `Executors.newFixedThreadPool(1)` and run `parallel`. The time returns to 1200 ms: "asynchronous"
  does not mean parallel when the pool has too few threads.

---

## Demo 7 — Semaphore and CountDownLatch

File: [SemaphoreConcurrencyLimitDemo.java](../demos/SemaphoreConcurrencyLimitDemo.java)

```bash
java -cp out SemaphoreConcurrencyLimitDemo broken
java -cp out SemaphoreConcurrencyLimitDemo fixed
```

```
mode=broken tasks=20 downstreamCapacity=3
peakConcurrentCalls=20 elapsed=109 ms
RESULT: WRONG (downstream overloaded)

mode=fixed tasks=20 downstreamCapacity=3
peakConcurrentCalls=3 elapsed=732 ms
RESULT: CORRECT (downstream never saw more than its capacity)
```

The broken version is 7 times faster, and wrong: it pushes 20 calls at once into a service that can take 3.
"Faster" is not always "better".

Find the three roles in the code: `startGate` (a latch of 1, releasing 20 tasks together), `allDone` (a latch of 20,
`main` waits for all), `permits` (a semaphore with 3 permits).

### Try more

- Move `permits.release()` out of `finally` and make `callDownstream` throw on its 5th call. Permits leak and the demo hangs.
- Remove `startGate.await()`. The broken version's `peakConcurrentCalls` may drop below 20: nothing guarantees the
  tasks collide any more. That is why concurrency tests need a start gate.

---

## Demo 8 — Virtual threads

File: [VirtualThreadsBlockingIoDemo.java](../demos/VirtualThreadsBlockingIoDemo.java)

```bash
java -cp out VirtualThreadsBlockingIoDemo platform
java -cp out VirtualThreadsBlockingIoDemo virtual
```

```
mode=platform(pool=100) tasks=2,000 blockMillis=200
completed=2,000 elapsed=4075 ms

mode=virtual tasks=2,000 blockMillis=200
completed=2,000 elapsed=230 ms
```

Check it by hand: 2000 tasks / 100 threads = 20 waves of 200 ms each, giving 4 seconds. With virtual threads all 2000
tasks wait at once.

### Try more

- Raise `TASKS` to 100,000 and run `virtual`. Measured: 554 ms for 100,000 tasks. Try the same with
  `Executors.newCachedThreadPool()` (one platform thread per task) to see the limit of platform threads.
- Replace `Thread.sleep(BLOCK_MILLIS)` with a 200 ms computation loop. Virtual threads lose their advantage: CPU-bound
  work does not release the carrier.

---

## Summary

| Demo | Symptom | What finds it | Fix |
|------|---------|---------------|-----|
| 1 | Wrong numbers, different each time | Reading the code: an unprotected shared variable | Atomics |
| 2 | A thread does not stop, CPU at 100% | Thread dump: `RUNNABLE`, standing still | `volatile` |
| 3 | Hang, CPU at 0% | Thread dump: "Found deadlock" | Lock ordering |
| 4 | Wrong numbers despite a thread-safe class | Reading the code: a compound action | `merge`, `computeIfAbsent` |
| 5 | Hang, CPU at 0%, no deadlock | Thread dump: pool threads waiting for a `FutureTask`; pool state | Separate pools, do not block |
| 6 | Slow | Measuring time | Run independent work in parallel |
| 7 | An overloaded downstream service | Measuring concurrency | `Semaphore` |
| 8 | Low throughput although the CPU is idle | Measuring time; the number of blocked threads | Virtual threads |

Two groups of bugs, two ways to find them:

- **Wrong results** (1, 4): thread dumps do not help. Go through every shared variable with the
  [checklist in lesson 9](09-debugging-thread-dumps-and-troubleshooting.md#8-review-checklist-for-concurrent-code).
- **Hangs or slowness** (2, 3, 5): the thread dump is the first tool.

Next step: take a real service you work on, list every mutable field in its singleton beans and every thread pool with
its queue configuration, and walk through the checklist in lesson 9.
