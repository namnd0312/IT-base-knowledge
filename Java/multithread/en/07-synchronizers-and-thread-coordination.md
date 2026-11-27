# 07 — Synchronizers and thread coordination

Goal: after this lesson you know how to make threads **wait for each other on a condition** ("wait until all 5
services are ready", "at most 3 calls at once"), use the right ready-made tool, and understand `wait`/`notify` well
enough to read legacy code.

Locks (lesson 3) answer "who may enter". Synchronizers answer "when may you proceed".

## 1. The tool map

| Need | Tool |
|------|------|
| Wait for N things to finish, or open a gate for many threads to start together | `CountDownLatch` |
| Limit how many threads do something at once | `Semaphore` |
| Several threads wait for each other at a rendezvous point, round after round | `CyclicBarrier` |
| Hand work between producers and consumers | `BlockingQueue` (lesson 4) |
| Wait for an arbitrary condition on your own state | `Condition`, or `wait`/`notify` |

The priority: use a ready-made tool first. Write `wait`/`notify` yourself only when none of them fits.

## 2. `CountDownLatch`

A counter that only goes down. A thread calling `await()` is blocked until the counter reaches 0. **Single-use**; it
cannot be reset.

```java
CountDownLatch ready = new CountDownLatch(3);
for (int i = 0; i < 3; i++) {
    new Thread(ready::countDown).start();
}
boolean allReady = ready.await(5, TimeUnit.SECONDS);    // false if 5 seconds pass without reaching 0
```

Two usages, both present in [SemaphoreConcurrencyLimitDemo.java](../demos/SemaphoreConcurrencyLimitDemo.java):

```java
CountDownLatch startGate = new CountDownLatch(1);      // start gate: count 1, opened once by main
CountDownLatch allDone = new CountDownLatch(TASKS);    // finish line: each task counts down once

// in each task
startGate.await();          // every task waits here
...
allDone.countDown();        // in finally

// main
startGate.countDown();      // open the gate: 20 tasks start at the same instant
allDone.await();            // wait for all 20
```

Notes:

- `countDown()` must be in `finally`. A task that throws without counting down leaves the `await()`ing thread waiting forever.
- Always use the timed `await` in production code.
- A start gate is the way to write trustworthy concurrency tests: it packs every thread into the same instant to expose race conditions.

## 3. `Semaphore`

Holds a number of **permits**. `acquire()` takes one, waiting if none is left. `release()` returns one. Used to limit concurrency.

Demo: 20 tasks call a downstream service that can only take 3 calls at a time.

```
mode=broken tasks=20 downstreamCapacity=3
peakConcurrentCalls=20 elapsed=109 ms
RESULT: WRONG (downstream overloaded)
```

All 20 calls land at once. Fast, and a good way to bring the downstream service down.

```java
Semaphore permits = new Semaphore(DOWNSTREAM_CAPACITY);

permits.acquire();              // blocks while 3 calls are already in flight
try {
    callDownstream();
} finally {
    permits.release();          // always release in finally, or permits leak
}
```

```
mode=fixed tasks=20 downstreamCapacity=3
peakConcurrentCalls=3 elapsed=732 ms
RESULT: CORRECT (downstream never saw more than its capacity)
```

Slower (20 calls, 3 per wave, 100 ms per wave: 7 waves), and that is the right price: throughput is set by the real
capacity of the system behind.

To avoid waiting indefinitely, try with a deadline and reject early:

```java
if (permits.tryAcquire(500, TimeUnit.MILLISECONDS)) {
    try { ... } finally { permits.release(); }
} else {
    // overloaded: return a "busy" error instead of queueing forever
}
```

| | `Semaphore` | `synchronized` / `Lock` |
|---|---|---|
| Threads allowed in | N | 1 |
| Who may release | Any thread | Only the holder |
| Reentrant | No: calling `acquire` twice costs two permits | Yes |

A `Semaphore` is also the standard concurrency limit with virtual threads, where there is no pool size left to act as
a limit (lesson 8).

## 4. `CyclicBarrier`

A rendezvous point for a fixed group: each thread calls `await()` and waits until **the whole group has arrived**;
then all proceed together and the barrier resets itself for the next round.

```java
CyclicBarrier barrier = new CyclicBarrier(3, () -> System.out.println("all 3 arrived, round starts"));
for (int i = 0; i < 3; i++) {
    new Thread(() -> {
        try {
            barrier.await();
        } catch (InterruptedException | BrokenBarrierException e) { }
    }).start();
}
```

| | `CountDownLatch` | `CyclicBarrier` |
|---|---|---|
| Who waits for whom | One or more threads wait for an **event** (the count reaching 0) | The threads of the group wait for **each other** |
| Reusable | No | Yes |
| One thread fails | You must guarantee `countDown` yourself | The barrier "breaks": every waiting thread gets `BrokenBarrierException` |
| Example | Wait for startup; wait for a batch of tasks | Step-wise simulation; round-based computation |

Two less common tools: `Phaser` (a barrier with a changing number of parties and several phases) and `Exchanger` (two
threads swap data at a rendezvous point).

## 5. `wait` / `notify`

This is the primitive mechanism everything above is built on. You rarely write it yourself today, but you need to
understand it to read legacy code and answer interview questions.

| Method | What it does |
|--------|--------------|
| `lock.wait()` | **Releases the lock** `lock` and sleeps until woken; on waking it re-acquires the lock before continuing |
| `lock.notify()` | Wakes **one** thread `wait`ing on `lock` (you cannot choose which) |
| `lock.notifyAll()` | Wakes **all** of them |

All three may only be called while holding the lock of that very object (inside `synchronized`); otherwise they throw
`IllegalMonitorStateException`.

A correct bounded buffer:

```java
static class BoundedBuffer<T> {
    private final Queue<T> items = new ArrayDeque<>();
    private final int capacity;

    BoundedBuffer(int capacity) { this.capacity = capacity; }

    public synchronized void put(T item) throws InterruptedException {
        while (items.size() == capacity) {      // while, NOT if
            wait();
        }
        items.add(item);
        notifyAll();
    }

    public synchronized T take() throws InterruptedException {
        while (items.isEmpty()) {
            wait();
        }
        T item = items.remove();
        notifyAll();
        return item;
    }
}
```

### Three rules

1. **Always `wait` in a `while` loop, never an `if`.** When a thread wakes up, the condition may be false again:
   another thread took the element while it was re-acquiring the lock, and the JVM is even allowed to wake a thread
   for no reason (a spurious wakeup). With an `if`, `take()` would call `remove()` on an empty queue.
2. **Prefer `notifyAll` over `notify`.** `notify` may wake the wrong thread (a producer waking another producer while
   the buffer is still full), and the signal is effectively lost: every thread sleeps forever.
3. **Checking the condition, waiting, and changing the state must use the same lock.** Otherwise the signal can arrive
   exactly in the gap between "check" and "start waiting" and be missed.

| | `wait()` | `Thread.sleep()` |
|---|---|---|
| Releases the lock | Yes | **No** |
| Wakes up when | Notified, timed out, interrupted | Timed out, interrupted |
| Where it may be called | Inside `synchronized` | Anywhere |

## 6. `Condition`

The `ReentrantLock` counterpart of `wait`/`notify`, with one advantage: **several separate wait queues on the same lock**.

```java
private final ReentrantLock lock = new ReentrantLock();
private final Condition notFull = lock.newCondition();
private final Condition notEmpty = lock.newCondition();

void put(T item) throws InterruptedException {
    lock.lock();
    try {
        while (items.size() == capacity) {
            notFull.await();            // producers wait in the "not full" queue
        }
        items.add(item);
        notEmpty.signal();              // wakes a consumer only
    } finally {
        lock.unlock();
    }
}
```

Producers and consumers wait in two different queues, so `signal()` always wakes the right kind of thread instead of
waking everyone as `notifyAll` does. The JDK's `ArrayBlockingQueue` is written exactly this way. The method names
differ to avoid confusion with those of `Object`: `await` / `signal` / `signalAll`.

## 7. Producer–consumer: which approach

| Approach | When |
|----------|------|
| A bounded `BlockingQueue` | The default. Correct, short, with backpressure |
| A thread pool | When the "consumer" is just "run this task" |
| `Condition` | When the wait condition is more complex than "empty/full" |
| `wait`/`notify` | Legacy code; learning |

## 8. Self-check

1. You must wait for 5 services to start before accepting requests. What do you use?
2. Why must `wait()` be in a `while` and not an `if`?
3. Forgetting to `release()` a `Semaphore` on exceptions: what is the long-term symptom?
4. How does `Thread.sleep(1000)` inside a `synchronized` block differ from `wait(1000)`?

Short answers: (1) `CountDownLatch(5)`. (2) The condition may no longer hold on waking: another thread got in, or the
wakeup was spurious. (3) Permits dwindle; the system slows down and then hangs entirely even without load. (4) `sleep`
keeps the lock for the whole second, blocking every other thread that needs it; `wait` releases the lock while waiting.

Next: [08 — Virtual threads](08-virtual-threads.md).
