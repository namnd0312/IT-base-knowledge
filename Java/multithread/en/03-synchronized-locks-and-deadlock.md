# 03 — synchronized, locks and deadlock

Goal: after this lesson you can protect shared state with a lock, choose between `synchronized` and `ReentrantLock`,
and know why deadlock happens and how to design it out.

## 1. The idea of a lock

A lock turns a piece of code into a **critical section**: only one thread may be inside it at a time. The read,
compute and write steps can no longer be interleaved, and thanks to the happens-before rule (lesson 2), a thread
entering later is guaranteed to see what the earlier thread wrote.

A question you must be able to answer for every shared variable: **which lock protects it?** If you cannot answer, or
the answer differs from place to place, the code is wrong.

## 2. `synchronized`

Every Java object has a built-in lock (the intrinsic lock, also called a monitor).

```java
static class SyncCounter {
    private int value;
    public synchronized void increment() { value++; }   // locks on this
    public synchronized int get() { return value; }     // reads must lock too
}
```

| Form | Locks on |
|------|----------|
| `synchronized void m()` | `this` |
| `static synchronized void m()` | The `Class` object (for example `Counter.class`) |
| `synchronized (obj) { ... }` | `obj` |

Properties:

- **Released automatically**: leaving the block, even by exception, releases the lock. You cannot forget.
- **Reentrant**: a thread holding the lock that calls another `synchronized` method on the same object does not block itself.
- **Not interruptible, no timeout**: a thread waiting for the lock waits forever.

### Common `synchronized` mistakes

| Mistake | Why it is wrong |
|---------|-----------------|
| Locking writes but not reads | The reader has no happens-before and may see a stale value |
| Two methods using two different locks for the same data | No mutual exclusion |
| `synchronized (new Object())` or locking on a local variable | Each thread gets its own lock: nothing is protected |
| Locking on a `String` literal, `Integer`, `Boolean` | These objects are shared JVM-wide; unrelated code may contend for the same lock |
| Locking on a field that can be reassigned | After reassignment, other threads lock on a different object |
| Locking on `this` in a public class | Outside code can `synchronized (yourObject)` too and stall you |

Good practice: a dedicated `private final` lock object:

```java
private final Object lock = new Object();

void update() {
    synchronized (lock) { ... }
}
```

### Hold the lock as briefly as possible

Inside the locked block, touch shared state only. Do **not** perform I/O (database, HTTP, file writes) and do not call
foreign code (callbacks, listeners) while holding a lock: every other thread needing that lock queues up for the whole
duration of the I/O, and foreign code may take further locks, opening the door to deadlock.

## 3. `ReentrantLock`

Same semantics as `synchronized`, plus capabilities `synchronized` lacks. In exchange, you release the lock yourself.

```java
static class LockCounter {
    private final ReentrantLock lock = new ReentrantLock();
    private int value;

    void increment() {
        lock.lock();
        try {
            value++;
        } finally {
            lock.unlock();          // MUST be in finally
        }
    }

    boolean tryIncrement() throws InterruptedException {
        if (!lock.tryLock(200, TimeUnit.MILLISECONDS)) {
            return false;           // no lock within 200 ms: give up instead of hanging
        }
        try {
            value++;
            return true;
        } finally {
            lock.unlock();
        }
    }
}
```

| | `synchronized` | `ReentrantLock` |
|---|---|---|
| Release | Automatic | Manual, in `finally` |
| Try, with timeout | No | `tryLock()`, `tryLock(timeout)` |
| Interruptible while waiting | No | `lockInterruptibly()` |
| Fairness (longest waiter first) | No | `new ReentrantLock(true)`, slower |
| Multiple wait conditions | One (`wait`/`notify`) | Several `Condition`s |
| Blocking inside pins a virtual thread (JDK 21–23) | Yes | No |

How to choose: default to `synchronized` because it is simple and cannot be left locked. Switch to `ReentrantLock`
when you need a timeout, interruptibility, several `Condition`s, or when the locked section performs a blocking
operation on a virtual thread (lesson 8).

Forgetting `unlock()` (or placing it outside `finally`) means one exception leaves the lock held forever; every later
thread hangs.

## 4. `ReadWriteLock`

For many reads and few writes: several threads may **read at once**, while a write is exclusive.

```java
static class PriceCache {
    private final ReadWriteLock rw = new ReentrantReadWriteLock();
    private final Map<String, Long> prices = new HashMap<>();

    Long get(String sku) {
        rw.readLock().lock();
        try { return prices.get(sku); } finally { rw.readLock().unlock(); }
    }

    void put(String sku, long price) {
        rw.writeLock().lock();
        try { prices.put(sku, price); } finally { rw.writeLock().unlock(); }
    }
}
```

It only pays off when the read section is long enough and reads dominate; a read–write lock is itself heavier than a
plain lock. For a simple map like the one above, `ConcurrentHashMap` (lesson 4) is usually the better choice. A read
lock cannot be upgraded to a write lock: a thread holding the read lock that requests the write lock hangs itself.

## 5. Deadlock

Demo: [DeadlockTransferDemo.java](../demos/DeadlockTransferDemo.java). Two threads transfer money in opposite directions between two accounts.

```java
static void transferBroken(Account from, Account to, long amount) throws InterruptedException {
    synchronized (from) {
        Thread.sleep(100);
        synchronized (to) {
            from.balance -= amount;
            to.balance += amount;
        }
    }
}
```

```
Thread 1: transfer(A, B)              Thread 2: transfer(B, A)
   lock A  ✔                             lock B  ✔
   wait for B  ✖ (held by thread 2)      wait for A  ✖ (held by thread 1)
              └──────────── waiting for each other forever ────────────┘
```

```
PID=52110 mode=broken
DEADLOCK detected by ThreadMXBean:
  "transfer-A-to-B" BLOCKED, waiting for lock held by "transfer-B-to-A"
  "transfer-B-to-A" BLOCKED, waiting for lock held by "transfer-A-to-B"
RESULT: WRONG (deadlock, transfers never finish)
```

No exception, no log, CPU at zero. The application simply stands still. In production the symptom is requests hanging
one by one until the thread pool is exhausted.

### The four conditions

Deadlock happens only when **all four** conditions hold. Breaking one is enough.

| Condition | Meaning | How to break it |
|-----------|---------|-----------------|
| Mutual exclusion | A resource can be held by one thread only | Lock-free structures, immutable data |
| Hold and wait | Holding one lock while requesting another | Hold one lock at a time; take all needed locks in one step |
| No preemption | Nobody can take a lock away from its holder | `tryLock` with a timeout: on failure release everything, back off, retry |
| Circular wait | A waits for B, B waits for A | **A global lock order** |

### Fixing it with lock ordering

The most effective and most common approach: everywhere in the system, locks are taken in the same order.

```java
static void transferFixed(Account from, Account to, long amount) throws InterruptedException {
    Account first = from.id < to.id ? from : to;
    Account second = from.id < to.id ? to : from;
    synchronized (first) {
        Thread.sleep(100);
        synchronized (second) {
            from.balance -= amount;
            to.balance += amount;
        }
    }
}
```

Both threads now lock account 1 first and account 2 second. The later thread waits at the first lock, where it holds
nothing yet, so it cannot form a cycle.

```
PID=52113 mode=fixed
balances: A=1100 B=900 total=2000
RESULT: CORRECT (both transfers finished)
```

### Less obvious sources of deadlock

- **Calling foreign code while holding a lock**: listeners, callbacks, overridable methods. You do not know which locks they take.
- **Two classes calling each other**, each `synchronized` on itself.
- **Database locks** combined with locks in code: two transactions updating two rows in opposite order.
- **A thread pool waiting on itself**: no lock is involved, so deadlock detection reports nothing (lesson 5).

## 6. Livelock and starvation

| | Deadlock | Livelock | Starvation |
|---|---|---|---|
| What the threads do | Stand still waiting | Run continuously without progress | One thread never gets its turn |
| CPU | 0% | High | Varies |
| Example | Two threads waiting for each other's lock | Two threads both fail `tryLock`, both back off, both retry in step | A low-priority thread, or an unfair lock under heavy load |
| Fix direction | Lock ordering | A random back-off before retrying | Fair locks, shorter lock hold times |

## 7. The cost of locks

Locks are correct but not free:

- **Contention**: a thread waiting for a lock is a thread doing nothing. The coarser the lock (the more data it
  covers) and the longer it is held, the more the system behaves as if single-threaded.
- **Context switching**: blocking a thread and waking it up costs operating-system time.

The design priority: do not share → share immutable data → use existing concurrent structures → lock.
The first three are the subject of lesson 4.

## 8. Self-check

1. `increment()` is `synchronized`, `get()` is not. What is the problem?
2. Why must `unlock()` be in `finally`?
3. Two threads, two locks. Which of the four conditions is broken by lock ordering?
4. The application hangs with CPU at 0%. And the application hangs with CPU at 100%. What do you suspect first in each case?

Short answers: (1) `get()` may return a stale value because it has no happens-before with the write. (2) If code
between `lock()` and `unlock()` throws, the lock stays held forever. (3) Circular wait. (4) CPU 0%: deadlock, or every
thread waiting (including a pool stuck on itself). CPU 100%: a loop that never ends (a visibility bug) or livelock.

Next: [04 — Atomics, concurrent collections and thread-safety strategies](04-atomics-concurrent-collections-and-thread-safety-strategies.md).
