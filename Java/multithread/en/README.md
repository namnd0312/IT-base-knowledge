# Java Multithreading — from zero to writing and debugging concurrent code

A self-study guide to multithreading in Java: what a thread is, why concurrent code fails in ways single-threaded
code never does, the tools for getting it right (locks, atomics, thread pools, `CompletableFuture`, virtual
threads), and how to track down the cause when an application hangs or produces wrong results.
It comes with 8 [runnable demos](../demos/), each with a broken and a fixed version; the output in the lessons is
taken from them.

Verified version: **JDK 21.0.5** (macOS, 8 cores). Specific numbers (lost updates, timings) will differ on your
machine; the conclusions will not.

Tiếng Việt: [../vn/README.md](../vn/README.md)

## Reading path

| # | Lesson | Content | Time |
|---|--------|---------|------|
| 1 | [01-thread-foundations.md](01-thread-foundations.md) | Process and thread, creating threads, lifecycle, `join`, `interrupt`, daemon | 30 min |
| 2 | [02-race-conditions-visibility-and-the-java-memory-model.md](02-race-conditions-visibility-and-the-java-memory-model.md) | The three root failures: atomicity, visibility, ordering; `volatile`; happens-before | 40 min |
| 3 | [03-synchronized-locks-and-deadlock.md](03-synchronized-locks-and-deadlock.md) | `synchronized`, `ReentrantLock`, `ReadWriteLock`, deadlock and how to avoid it | 45 min |
| 4 | [04-atomics-concurrent-collections-and-thread-safety-strategies.md](04-atomics-concurrent-collections-and-thread-safety-strategies.md) | Atomics, CAS, `ConcurrentHashMap`, `BlockingQueue`, immutability, `ThreadLocal` | 40 min |
| 5 | [05-thread-pools-and-executors.md](05-thread-pools-and-executors.md) | `ExecutorService`, `ThreadPoolExecutor` parameters, pool sizing, shutdown | 45 min |
| 6 | [06-completablefuture.md](06-completablefuture.md) | Composing asynchronous tasks, error handling, timeouts | 40 min |
| 7 | [07-synchronizers-and-thread-coordination.md](07-synchronizers-and-thread-coordination.md) | `wait`/`notify`, `CountDownLatch`, `Semaphore`, `CyclicBarrier`, producer–consumer | 35 min |
| 8 | [08-virtual-threads.md](08-virtual-threads.md) | Virtual threads (JDK 21): when to use them, when not to, pinning | 30 min |
| 9 | [09-debugging-thread-dumps-and-troubleshooting.md](09-debugging-thread-dumps-and-troubleshooting.md) | Reading thread dumps, `jstack`/`jcmd`, lookup table, review checklist | 35 min |
| 10 | [10-hands-on-lab-with-demos.md](10-hands-on-lab-with-demos.md) | Lab with 8 demos: run broken → observe → find the cause → fix → verify | 60–90 min |

Suggestion: read 1 → 2, then run demos 1 and 2 from lesson 10 right away. Seeing 8 million additions turn into
fewer than 2 million makes the later lessons much easier to absorb.

Facing a real incident (application hangs, CPU at 100%, results sometimes right and sometimes wrong)? Go straight to
the [lookup table in lesson 9](09-debugging-thread-dumps-and-troubleshooting.md#7-common-errors-lookup-table).

## Knowledge map

```
                    What a thread is, how it lives and dies (01)
                                  │
                                  ▼
              Why sharing data between threads goes wrong (02)
                                  │
         ┌────────────────────────┼─────────────────────────┐
         ▼                        ▼                         ▼
   Lock it (03)           No lock needed (04)       Coordinate, wait (07)
   synchronized, Lock     atomics, concurrent       latch, semaphore,
   deadlock               collections, immutable    wait/notify
         └────────────────────────┼─────────────────────────┘
                                  ▼
     Don't create threads yourself: thread pools (05), CompletableFuture (06),
                        virtual threads (08)
                                  ▼
                 Debugging, thread dumps, checklist (09)
```

## Demos

| Demo | Failure reproduced | Lesson |
|------|--------------------|--------|
| [RaceConditionCounterDemo](../demos/RaceConditionCounterDemo.java) | Lost updates because `counter++` is not atomic | 2 |
| [VisibilityStopFlagDemo](../demos/VisibilityStopFlagDemo.java) | A thread never sees the stop flag because `volatile` is missing | 2 |
| [DeadlockTransferDemo](../demos/DeadlockTransferDemo.java) | Deadlock from two threads taking locks in opposite order | 3 |
| [CheckThenActConcurrentMapDemo](../demos/CheckThenActConcurrentMapDemo.java) | A `ConcurrentHashMap` used as "read then write" still loses data | 4 |
| [ThreadPoolStarvationDeadlockDemo](../demos/ThreadPoolStarvationDeadlockDemo.java) | A pool stuck on itself: tasks wait for subtasks queued in the same pool | 5 |
| [CompletableFutureCompositionDemo](../demos/CompletableFutureCompositionDemo.java) | Sequential 1200 ms vs parallel 500 ms; fallback on failure | 6 |
| [SemaphoreConcurrencyLimitDemo](../demos/SemaphoreConcurrencyLimitDemo.java) | 20 calls hitting a service that can take 3 at a time | 7 |
| [VirtualThreadsBlockingIoDemo](../demos/VirtualThreadsBlockingIoDemo.java) | 2000 I/O-bound tasks: a 100-thread pool takes 4 s, virtual threads 0.2 s | 8 |

## Quick start

Requirements: JDK 21 or later (demo 8 uses virtual threads). No Maven needed.

```bash
cd Java/multithread
javac -d out demos/*.java

java -cp out RaceConditionCounterDemo broken   # wrong: millions of additions lost
java -cp out RaceConditionCounterDemo fixed    # right: 8,000,000
```

```
mode=broken threads=8 incrementsPerThread=1,000,000
expected=8,000,000 actual=1,963,442 lost=6,036,558
RESULT: WRONG (lost updates)
```

Every demo takes a mode argument (`broken`/`fixed`; demos 6 and 8 use other mode names, see lesson 10) and prints a
`RESULT:` line at the end. `out/` is ignored in [.gitignore](../.gitignore).
