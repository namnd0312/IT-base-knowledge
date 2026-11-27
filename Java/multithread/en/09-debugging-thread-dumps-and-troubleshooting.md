# 09 — Debugging, thread dumps and troubleshooting

Goal: after this lesson you can take and read a thread dump, recognise the three most common incident patterns
(deadlock, exhausted pool, runaway loop), and you have a lookup table and a checklist for reviewing concurrent code.

## 1. Why concurrency debugging is different

Breakpoints and `println` change the timing of the threads, so the bug tends to disappear exactly when you look. The
broken version of the visibility demo stops normally once a `println` is added inside the loop, and also when run with
`-Xint` (JIT off). Neither is a fix.

The main tool is therefore the **thread dump**: a snapshot of what every thread is doing at one moment, taken from
outside, without changing the program's behaviour.

## 2. Taking a thread dump

| Command | Notes |
|---------|-------|
| `jcmd -l` or `jps -l` | Find the PID of the Java process |
| `jstack <pid>` | Prints the dump to the screen |
| `jcmd <pid> Thread.print` | Equivalent to `jstack`, and the currently recommended way |
| `jcmd <pid> Thread.dump_to_file -format=json <file>` | Includes virtual threads (JDK 21) |
| `kill -3 <pid>` | The dump goes to the process's own `stdout`; for machines without a full JDK |

```bash
jcmd <pid> Thread.print > dump-1.txt
```

```
52307:
2026-11-27 00:42:22
Full thread dump Java HotSpot(TM) 64-Bit Server VM (21.0.5+9-LTS-239 mixed mode, sharing):
```

**Take three, about 5 seconds apart.** One dump is a single instant. A thread standing in the same place across all
three is stuck; a thread that moves is working normally.

## 3. Reading one thread in a dump

```
"transfer-A-to-B" #20 [29187] daemon prio=5 os_prio=31 cpu=0.16ms elapsed=2.22s tid=0x... nid=29187 waiting for monitor entry
   java.lang.Thread.State: BLOCKED (on object monitor)
	at DeadlockTransferDemo.transferBroken(DeadlockTransferDemo.java:35)
	- waiting to lock <0x000000070fc2ce78> (a DeadlockTransferDemo$Account)
	- locked <0x000000070fc2ce60> (a DeadlockTransferDemo$Account)
	at DeadlockTransferDemo.run(DeadlockTransferDemo.java:92)
```

| Part | Meaning |
|------|---------|
| `"transfer-A-to-B"` | The thread name. The reason meaningful names matter |
| `daemon` | It is a daemon thread |
| `cpu=0.16ms elapsed=2.22s` | Alive for 2.22 seconds but used only 0.16 ms of CPU: it is almost entirely waiting |
| `Thread.State: BLOCKED` | The state (lesson 1) |
| The first `at ...` line | Where it stands. Read top down: the top is the current position |
| `- waiting to lock <0x...78>` | It wants the lock of the object at this address |
| `- locked <0x...60>` | It holds the lock of this object |

The ratio of `cpu` to `elapsed` is the quickest clue: near 0 means waiting, near 100% means spinning.

Reading by state:

| State + top of the stack | The thread is |
|--------------------------|---------------|
| `RUNNABLE`, in your code | Computing. Standing on one line across several dumps: a runaway loop |
| `RUNNABLE`, in `socketRead`, `SocketDispatcher.read` | Waiting for network data (database, HTTP). The JVM still reports RUNNABLE |
| `BLOCKED`, with `waiting to lock` | Waiting for a `synchronized` lock. Find the thread whose `locked` line has the same address |
| `WAITING (parking)`, in `ThreadPoolExecutor.getTask` | An idle pool thread waiting for work. Normal |
| `WAITING (parking)`, in `FutureTask.get`, `CompletableFuture.join` | Waiting for the result of another task |
| `WAITING (parking)`, in `ReentrantLock.lock` | Waiting for a `ReentrantLock` |
| `WAITING (on object monitor)`, in `Object.wait` | Waiting for `notify` |
| `TIMED_WAITING`, in `Thread.sleep` | Sleeping |

## 4. Three incident patterns

### Pattern 1: deadlock

Run `java -DhangSeconds=60 -cp out DeadlockTransferDemo broken`, then `jstack <pid>`. At the end of the dump the JVM
draws the conclusion itself:

```
Found one Java-level deadlock:
=============================
"transfer-A-to-B":
  waiting to lock monitor 0x0000600001e4c000 (object 0x000000070fc2ce78, a DeadlockTransferDemo$Account),
  which is held by "transfer-B-to-A"

"transfer-B-to-A":
  waiting to lock monitor 0x0000600001e48270 (object 0x000000070fc2ce60, a DeadlockTransferDemo$Account),
  which is held by "transfer-A-to-B"

Java stack information for the threads listed above:
===================================================
"transfer-A-to-B":
	at DeadlockTransferDemo.transferBroken(DeadlockTransferDemo.java:35)
	- waiting to lock <0x000000070fc2ce78> (a DeadlockTransferDemo$Account)
	- locked <0x000000070fc2ce60> (a DeadlockTransferDemo$Account)
...
"transfer-B-to-A":
	at DeadlockTransferDemo.transferBroken(DeadlockTransferDemo.java:35)
	- waiting to lock <0x000000070fc2ce60> (a DeadlockTransferDemo$Account)
	- locked <0x000000070fc2ce78> (a DeadlockTransferDemo$Account)

Found 1 deadlock.
```

Cross-read the two addresses: thread 1 holds `...60` and wants `...78`; thread 2 holds `...78` and wants `...60`. That
is the cycle. The dump also gives the line of code (`:35`) and the type of the locked object, enough to find the fix.

The first thing to do when an application hangs: search the dump for `Found one Java-level deadlock`. The JVM detects
deadlocks of both `synchronized` and `ReentrantLock`.

### Pattern 2: an exhausted pool, every thread waiting

Run `java -DwaitSeconds=60 -cp out ThreadPoolStarvationDeadlockDemo broken`, then `jstack <pid>`:

```
"pool-1-thread-1" #20 [29955] prio=5 os_prio=31 cpu=0.35ms elapsed=2.17s ... waiting on condition
   java.lang.Thread.State: WAITING (parking)
	at jdk.internal.misc.Unsafe.park(java.base@21.0.5/Native Method)
	- parking to wait for  <0x000000070fc02b50> (a java.util.concurrent.FutureTask)
	at java.util.concurrent.locks.LockSupport.park(java.base@21.0.5/LockSupport.java:221)
	at java.util.concurrent.FutureTask.awaitDone(java.base@21.0.5/FutureTask.java:500)
	at java.util.concurrent.FutureTask.get(java.base@21.0.5/FutureTask.java:190)
	at ThreadPoolStarvationDeadlockDemo.processOrder(ThreadPoolStarvationDeadlockDemo.java:54)
	...
	at java.util.concurrent.ThreadPoolExecutor.runWorker(java.base@21.0.5/ThreadPoolExecutor.java:1144)
```

This dump has **no** "Found deadlock" line: no lock is involved. The tell-tale sign is the pattern:

- **Every** thread of one pool is in the same state with the same stack.
- The stack runs from `ThreadPoolExecutor.runWorker` (a pool thread) up to `FutureTask.get` (waiting for another task).

The same pattern, differing only in the top frame, points to other causes:

| Every pool thread stands in | Cause |
|-----------------------------|-------|
| `FutureTask.get`, `CompletableFuture.join` | Tasks waiting for subtasks of the same pool (lesson 5) |
| `socketRead` | A slow or hung downstream service, missing timeouts |
| `HikariPool.getConnection` or similar | Out of database connections: the connection pool is smaller than demand, or connections leak |
| `waiting to lock` on one address | Contention for one lock; find the holder and see what it is doing |

### Pattern 3: a runaway loop

Run `java -cp out VisibilityStopFlagDemo broken` and take a dump during the 3 seconds it is still running:

```
"spinner" #20 [28931] daemon prio=5 os_prio=31 cpu=2169.71ms elapsed=2.17s ... runnable
   java.lang.Thread.State: RUNNABLE
	at VisibilityStopFlagDemo.lambda$main$0(VisibilityStopFlagDemo.java:33)
```

`cpu=2169ms` against `elapsed=2.17s`: this thread has used a full core for its whole life. `RUNNABLE`, in your code,
and standing on the same line in every dump.

Finding the CPU-hungry thread in a real process:

```bash
top -H -p <pid>          # Linux: per-thread listing, note the TID of the hungry thread
printf '%x\n' <tid>      # convert to hexadecimal
jstack <pid> | grep -A 20 'nid=0x<hex>'
```

On JDK 21 the `cpu=` field in the dump is usually enough: compare it between two dumps to see which thread is burning CPU.

## 5. A procedure for incidents

```
The application hangs or is slow
        │
        ▼
Is the process CPU high or low?
        │
   ┌────┴─────────────────────────────┐
   ▼ low (near 0)                     ▼ high
Take 3 thread dumps                Take 3 thread dumps
   │                                  │
   ├─ "Found deadlock"?               ├─ a RUNNABLE thread standing still in your code
   │     → pattern 1                  │     → runaway loop (visibility), livelock
   ├─ every pool thread waiting       ├─ many GC threads running
   │  in the same place?              │     → a memory problem, see Java/memory
   │     → pattern 2                  └─ many RUNNABLE threads in many different places
   └─ waiting on I/O (socketRead)?          → genuine overload, or a poor algorithm
         → downstream service, timeouts
```

**Wrong results** (drifting numbers, duplicated data) are not helped by thread dumps: that is a race condition, found
by reading the code against the checklist in section 8.

## 6. Other tools

| Tool | Use for |
|------|---------|
| `ThreadMXBean.findDeadlockedThreads()` | Detecting deadlocks from code (health checks). [DeadlockTransferDemo](../demos/DeadlockTransferDemo.java) uses it |
| `ThreadPoolExecutor.toString()` | A quick look at `pool size`, `active threads`, `queued tasks`, `completed tasks` |
| Pool metrics: `getActiveCount()`, `getQueue().size()` | Put them on a dashboard; a steadily growing queue is an early warning |
| JFR: `jcmd <pid> JFR.start duration=60s filename=rec.jfr` | Records lock contention, long-blocked threads, virtual-thread pinning; open with JDK Mission Control |
| VisualVM, the IntelliJ debugger (Threads tab) | Visual thread inspection during development |
| `-Djdk.tracePinnedThreads=full` (JDK 21–23) | Prints a stack when a virtual thread is pinned |
| jcstress | Testing the correctness of low-level synchronisation code |

### Writing tests for concurrent code

Tests cannot prove concurrent code correct, but they raise the chance of exposing a bug:

- Use a `CountDownLatch` as a start gate so every thread enters the suspect section together (lesson 7).
- More threads than cores, many repetitions, and a check of an **invariant** at the end (the total balance is
  unchanged, the count equals the expectation).
- Always give the test a timeout, or a deadlock hangs the whole pipeline.
- A green test does not mean correct. The visibility demo is "correct" with the JIT off.

## 7. Common errors lookup table

| Symptom | Usual cause | Fix |
|---------|-------------|-----|
| Counters, balances or stock drift under load | `x++`, read then write, unsynchronised | Atomics, `merge`, or a lock (lessons 2, 4) |
| Occasional duplicate records, double initialisation | Check-then-act | `computeIfAbsent`, `putIfAbsent`, a unique constraint in the database |
| A thread does not stop or pick up a new value | Missing `volatile` | `volatile`, or read under the lock (lesson 2) |
| The bug vanishes when debugging or adding logs | Visibility or a race | Do not trust "it went away"; find the unprotected shared variable |
| The application hangs, CPU near 0, "Found deadlock" | Two locks taken in opposite order | A global lock order, `tryLock` with a timeout (lesson 3) |
| Hangs, CPU near 0, every pool thread in `FutureTask.get` | Tasks waiting for subtasks of the same pool | Separate pools, or compose without blocking (lessons 5, 6) |
| Gradually hangs when a downstream service is slow | Missing timeouts; a shared pool | A timeout on every outbound call; one pool per dependency |
| `OutOfMemoryError` with a huge queue in the heap dump | The unbounded queue of `newFixedThreadPool` | A bounded queue + a rejection policy (lesson 5) |
| `OutOfMemoryError: unable to create native thread` | Unbounded thread creation (`new Thread` per job, `newCachedThreadPool`) | A pool with a ceiling |
| `RejectedExecutionException` | Pool and queue both full, or a task submitted after `shutdown()` | Treat it as an overload signal; check the pool's lifecycle |
| A background task fails with no log | `submit()` without `get()`; a `CompletableFuture` without error handling | A logging `try/catch` in the task; `exceptionally`/`handle` |
| A periodic task stops by itself | One run threw an exception | Wrap the whole task body in `try/catch` |
| The JVM does not exit after `main` finishes | A pool was not `shutdown()` | `shutdown()`, try-with-resources, or let the framework manage it |
| `shutdownNow()` cannot stop a task | The task swallows `InterruptedException` | Restore the interrupt flag and exit (lesson 1) |
| `ConcurrentModificationException` | Modifying a collection while iterating, or an `ArrayList`/`HashMap` used from several threads | Concurrent collections; iterate over a copy |
| `HashMap.get` returns `null` for a present key; 100% CPU inside `HashMap` | A `HashMap` written by several threads | `ConcurrentHashMap` |
| `IllegalMonitorStateException` | `wait`/`notify` outside `synchronized`, or `unlock` of a lock not held | Call them inside the locked block of the right object |
| One user's data shows up in another user's request | `ThreadLocal` without `remove()` in a pool; a mutable field in a singleton bean | `remove()` in `finally`; remove the state field (lesson 4) |
| Random wrong dates | A shared `SimpleDateFormat` | `DateTimeFormatter` (immutable) |
| `CompletableFuture` unusually slow, parallel streams hang | Blocking work on the common pool | Pass a dedicated executor (lesson 6) |
| Throughput drops after moving to virtual threads | Pinning from `synchronized` around I/O (JDK 21–23) | `ReentrantLock`, a newer JDK, updated drivers (lesson 8) |
| After moving to virtual threads the database reports too many connections | The implicit limit of the pool size is gone | `Semaphore`, connection pool (lesson 8) |
| Adding threads does not make it faster | CPU-bound work beyond the core count; contention on one lock; a large sequential part | Measure first; shrink the locked region; threads equal to cores |

## 8. Review checklist for concurrent code

**Shared state**

- [ ] For every mutable field: is it touched by more than one thread? If so, what protects it?
- [ ] Singleton beans (services, controllers) have no mutable state fields.
- [ ] Reads and writes of the same variable go through the same mechanism (the same lock, or `volatile`, or an atomic).
- [ ] No compound actions (`get` then `put`, check then act) on concurrent collections.
- [ ] Objects shared between threads are immutable wherever possible.

**Locks**

- [ ] Locks are on `private final` objects, not on a public `this`, a `String`, or a boxed type.
- [ ] `unlock()` is in `finally`.
- [ ] No I/O and no foreign code while holding a lock.
- [ ] Wherever more than one lock is taken, a fixed order is followed.

**Thread pools and asynchronous tasks**

- [ ] No `new Thread()` in business code.
- [ ] Pool queues are bounded; the rejection policy is a deliberate choice.
- [ ] Threads are named.
- [ ] Tasks do not block waiting for other tasks of the same pool.
- [ ] Every outbound call has a timeout; every `get()`/`await()` has a timeout.
- [ ] Blocking work does not run on the common pool.
- [ ] Every `Future`/`CompletableFuture` has someone handling its errors.
- [ ] Pools are shut down when the application stops.

**Interrupts and cleanup**

- [ ] `InterruptedException` is never swallowed.
- [ ] `ThreadLocal`s are `remove()`d in `finally`.
- [ ] `release()` and `countDown()` are in `finally`.

Next: [10 — Hands-on lab with the demos](10-hands-on-lab-with-demos.md).
