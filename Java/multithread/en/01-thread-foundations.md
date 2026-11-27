# 01 — Thread foundations

Goal: after this lesson you know what a thread is, can create and stop one correctly, can read a thread's state,
and understand why "running more threads" does not automatically mean "running faster".

## 1. Process and thread

| | Process | Thread |
|---|---|---|
| What it is | A running program | A flow of execution inside a process |
| Memory | Its own, isolated from other processes | **Shares the heap** with the other threads of the process |
| Private to it | The whole address space | Stack, program counter |
| Creation cost | High | Lower, but not free (each platform thread takes about 1 MB of stack) |
| Communication | Sockets, pipes, files | Reading and writing shared objects directly |

A JVM is one process. A "single-threaded" program already has several threads: `main`, GC threads, JIT threads...

The key point for this whole guide: **threads share the heap**. That is why they can cooperate so easily, and it is
also the origin of every concurrency bug (lesson 2).

```
┌──────────────────────────── JVM process ─────────────────────────────┐
│                                                                      │
│   HEAP (shared): objects, static fields                              │
│        ▲            ▲             ▲                                  │
│        │            │             │                                  │
│   ┌────┴────┐  ┌────┴────┐  ┌─────┴───┐                              │
│   │ Thread 1│  │ Thread 2│  │ Thread 3│   each thread: its own       │
│   │  stack  │  │  stack  │  │  stack  │   stack and local variables  │
│   └─────────┘  └─────────┘  └─────────┘                              │
└──────────────────────────────────────────────────────────────────────┘
```

Local variables live on the stack and are always safe. What you must worry about is **an object on the heap that
more than one thread touches**.

## 2. Concurrency and parallelism

| | Concurrency | Parallelism |
|---|---|---|
| Meaning | Several things **making progress together**, interleaved | Several things **running at the same instant** |
| Needs | One core is enough | Several cores |
| Example | One cook making three dishes, switching between them | Three cooks, one dish each |
| Benefit | Nobody waits for anybody: responsiveness | Finishing sooner: throughput |

Threads are used for two different reasons, and the tuning differs too:

- **Waiting on I/O** (database, HTTP calls): the thread mostly sits waiting. More threads let you serve more requests
  at once even with a few cores.
- **Computation** (compressing images, building reports): the thread uses CPU continuously. More threads than cores
  does not help; it only adds context-switching cost.

A limit worth knowing (Amdahl's law): if 20% of the work must run sequentially, no number of cores makes it more than
5 times faster. The sequential part, usually the code holding a lock, sets the performance ceiling.

## 3. Creating threads

```java
// Option 1: extend Thread (rare: mixes "what to do" with "how to run it")
static class DownloadThread extends Thread {
    @Override public void run() { System.out.println("running in " + Thread.currentThread().getName()); }
}

Thread t1 = new DownloadThread();

// Option 2: pass a Runnable (preferred); always name the thread
Thread t2 = new Thread(() -> System.out.println("hello from " + Thread.currentThread().getName()), "worker-1");

// Option 3: builder (JDK 21)
Thread t3 = Thread.ofPlatform().name("worker-2").daemon(true).unstarted(() -> {});

t1.start(); t2.start(); t3.start();
```

```
running in Thread-0
hello from worker-1
```

Three things to remember:

- **`start()`, not `run()`.** `start()` creates a new thread which then calls `run()`. Calling `run()` directly is an
  ordinary method call on the current thread; nothing runs in parallel.
- **Name your threads.** `Thread-0` in a thread dump tells you nothing; `payment-worker-3` does.
- **Real projects almost never call `new Thread`.** Use a thread pool (lesson 5). One new thread per job is the
  fastest way to run out of memory when load rises.

A `Thread` can be `start()`ed only once. A second call throws `IllegalThreadStateException`.

## 4. Lifecycle and states

```
                 start()
   NEW ───────────────────▶ RUNNABLE ──────────────────▶ TERMINATED
                              │  ▲                     run() returns
        waiting for a         │  │  got the lock
        synchronized lock     ▼  │
                            BLOCKED

        wait(), join(),       │  ▲  notify(), other thread done,
        park()                ▼  │  unpark()
                            WAITING

        sleep(n), wait(n),    │  ▲  timeout or woken up
        join(n)               ▼  │
                         TIMED_WAITING
```

| State | Meaning | Seen in a thread dump when |
|-------|---------|----------------------------|
| `NEW` | Created, not yet `start()`ed | Rarely |
| `RUNNABLE` | Running or ready to run. Includes waiting for I/O at the operating-system level | Computing, reading a socket |
| `BLOCKED` | Waiting to enter a `synchronized` block held by another thread | Lock contention, deadlock |
| `WAITING` | Waiting indefinitely for a signal from another thread | Idle pool thread, `future.get()` |
| `TIMED_WAITING` | Same, with a deadline | `sleep`, waiting with a timeout |
| `TERMINATED` | `run()` has finished | No longer in the dump |

Real output from reading `getState()` at each moment:

```
before start : NEW
sleeping     : TIMED_WAITING
in wait()    : WAITING
wants a lock : BLOCKED
after finish : TERMINATED
```

Telling `BLOCKED` from `WAITING` is the most important thread-dump skill (lesson 9): `BLOCKED` means "wants a lock and
cannot get it", `WAITING` means "voluntarily waiting for someone to call".

## 5. `join`, `sleep`, `yield`

| Method | Effect | Notes |
|--------|--------|-------|
| `t.join()` | The current thread waits for `t` to finish | There is a timed variant: `t.join(1000)` |
| `Thread.sleep(ms)` | The current thread sleeps | It **does not release** any lock it holds |
| `Thread.yield()` | A hint to give up the CPU | Only a hint; almost never needed |

```java
Thread worker = new Thread(task);
worker.start();
worker.join();          // without this, main may read the result before the worker is done
```

Do not use `sleep` to "wait until the other thread is done". A slightly slower machine breaks it. Use `join`, a
`Future`, or a `CountDownLatch` (lesson 7).

## 6. Stopping a thread: interrupt

Java has no safe way to force a thread to stop. `Thread.stop()` was abandoned because it kills the thread midway and
leaves data half-updated. The only way is **cooperation**: send a stop request; the thread checks for it and ends itself.

`thread.interrupt()` does one of two things depending on where the thread is:

- Blocked in `sleep`, `wait`, `join`, `BlockingQueue.take`...: that method throws `InterruptedException`
  **and clears the interrupt flag**.
- Running normally: it only sets the flag. The thread must check `isInterrupted()` itself.

The standard pattern:

```java
Thread worker = new Thread(() -> {
    while (!Thread.currentThread().isInterrupted()) {     // check the flag
        try {
            Thread.sleep(200);                            // work that may block
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();           // restore the cleared flag
            break;
        }
    }
    System.out.println("worker: cleaned up and stopped");
});
worker.start();
Thread.sleep(300);
worker.interrupt();
worker.join();
```

The most common interrupt bug is **swallowing the exception**:

```java
try {
    Thread.sleep(1000);
} catch (InterruptedException e) {
    // empty: the stop request vanishes and the thread carries on as if nothing happened
}
```

The rule: when you catch `InterruptedException`, either rethrow it, or call `Thread.currentThread().interrupt()` and
exit. Thread pools stop their threads with exactly this mechanism (`shutdownNow`), so code that swallows interrupts is
code that cannot be shut down.

## 7. Daemon threads

| | User thread (default) | Daemon thread |
|---|---|---|
| Does the JVM wait for it | Yes: the JVM exits only when every user thread has ended | No: when the JVM exits, it is cut off |
| Use for | Work that must complete | Background work that may be abandoned: cache cleanup, heartbeat |

```java
thread.setDaemon(true);   // must be called before start()
```

The symptom "the program finished `main` but does not exit": some user thread is still alive, usually a thread pool
that was never `shutdown()`. Conversely, do not use daemon threads for writing files or databases: they can be cut off midway.

## 8. Exceptions in a thread

An exception thrown inside `run()` does not travel back to the thread that created it. That thread dies, prints a stack
trace to `stderr`, and the rest of the program carries on as if nothing happened.

```java
thread.setUncaughtExceptionHandler((t, error) ->
        log.error("Thread {} died", t.getName(), error));
```

With thread pools it is even quieter: the exception of a task run with `submit()` sits inside the `Future` until
someone calls `get()` (lesson 5).

## 9. Self-check

1. What happens if you call `thread.run()` instead of `thread.start()`?
2. How does a `BLOCKED` thread differ from a `WAITING` one?
3. Why call `Thread.currentThread().interrupt()` inside `catch (InterruptedException e)`?
4. `main` has finished but the JVM does not exit. The most common cause?

Short answers: (1) `run()` executes on the current thread; no new thread exists. (2) `BLOCKED` is waiting to take a
`synchronized` lock; `WAITING` is waiting to be woken by another thread. (3) Because throwing the exception cleared
the interrupt flag; without restoring it, outer code cannot tell that a stop was requested. (4) A user thread is still
alive, usually a thread pool that was not `shutdown()`.

Next: [02 — Race conditions, visibility and the Java Memory Model](02-race-conditions-visibility-and-the-java-memory-model.md).
