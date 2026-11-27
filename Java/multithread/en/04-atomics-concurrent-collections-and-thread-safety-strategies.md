# 04 — Atomics, concurrent collections and thread-safety strategies

Goal: after this lesson you know the ways to achieve thread safety without writing locks yourself, use
`ConcurrentHashMap` and `BlockingQueue` correctly, and recognise the bug of "using a safe collection unsafely".

## 1. Four strategies, in priority order

A concurrency bug needs three ingredients at once: data that is **shared**, **mutable**, and **written** by some
thread. Each strategy removes one ingredient.

| # | Strategy | Removes | Examples |
|---|----------|---------|----------|
| 1 | Do not share (thread confinement) | Shared | Local variables, objects created per request, `ThreadLocal` |
| 2 | Immutability | Mutable | `record`, `String`, `List.of(...)`, `final` fields |
| 3 | Use existing thread-safe structures | Managing synchronisation yourself | `AtomicInteger`, `ConcurrentHashMap`, `BlockingQueue` |
| 4 | Lock | (removes nothing, only controls) | `synchronized`, `Lock` (lesson 3) |

The higher up, the fewer places to go wrong. Most web-service code should stop at 1 and 2: controllers and services
have no mutable fields; the data of each request lives in local variables.

## 2. Do not share

An object touched by a single thread needs no synchronisation at all.

```java
void handle(Request request) {
    List<Item> items = new ArrayList<>();     // local: only this thread sees it
    ...
}
```

Watch out for **reference leaks**: a local object stored in a static field, put into a shared cache, or handed to
another thread is no longer "private".

The classic Spring mistake: a singleton bean with a mutable field.

```java
@Service
class ReportService {
    private List<Row> rows = new ArrayList<>();   // WRONG: every request shares one list
    private SimpleDateFormat format = ...;        // WRONG: SimpleDateFormat is not thread-safe
}
```

### `ThreadLocal`

Each thread has its own copy of the variable.

```java
static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();

CURRENT_USER.set("an");
try {
    System.out.println(CURRENT_USER.get());
} finally {
    CURRENT_USER.remove();      // mandatory when the thread belongs to a pool
}
```

Pool threads live very long and are reused. Without `remove()`, the value of the previous request sticks to the next
one (leaking data between users), and the object is retained forever (a memory leak; see the
`ThreadLocalLeakInThreadPoolDemo` in [Java/memory](../../memory/)). A `ThreadLocal` value also does not follow along
when you hand work to a pool.

## 3. Immutability

An object that cannot change after construction is safe for any number of reading threads, with no lock and no
`volatile` for its contents.

```java
record Money(long amount, String currency) {
    Money plus(Money other) { return new Money(amount + other.amount, currency); }   // returns a new object
}
```

Conditions for real immutability:

- Every field is `final`; no setters.
- Collection fields must be unmodifiable copies: `List.copyOf(input)`. A `final List` fixes the reference, not the contents.
- `this` must not escape from the constructor (registering a listener, starting a thread).

A very useful pattern: **immutable state + one `volatile` reference**. To change the configuration, build a new object
and assign over the old one; readers always see a consistent version, old or new, never half and half.

```java
private volatile Config config = Config.load();

void reload() { config = Config.load(); }      // replaced as a whole
```

## 4. Atomics and CAS

```java
AtomicInteger counter = new AtomicInteger();
counter.incrementAndGet();                              // atomic ++
counter.updateAndGet(v -> Math.min(v * 2, 100));        // atomic read-compute-write
boolean swapped = counter.compareAndSet(2, 10);         // write 10 only if it is currently 2

LongAdder requests = new LongAdder();                   // a counter written very often, read rarely
requests.increment();
long total = requests.sum();

AtomicReference<List<String>> config = new AtomicReference<>(List.of("a"));
config.set(List.of("a", "b"));
```

Underneath is **CAS (compare-and-swap)**, a CPU instruction: "if the memory cell still holds the value I just read,
write the new value; otherwise report failure". `incrementAndGet` is a loop: read, compute, CAS; on failure (another
thread got in) do it again. No thread is ever blocked, so there is no deadlock.

| Class | Use when |
|-------|----------|
| `AtomicInteger`, `AtomicLong` | Counters, sequence generators |
| `LongAdder` | A counter written by very many threads (metrics). Faster than `AtomicLong` under high contention; `sum()` is not an exact snapshot |
| `AtomicBoolean` | A "do it only once" flag: `if (started.compareAndSet(false, true))` |
| `AtomicReference<T>` | Atomically replacing a whole immutable object |

The limit: an atomic guarantees **one variable**. Two `AtomicInteger`s that must stay consistent with each other (for
example `min <= max`) still need a lock, or both must be merged into one immutable object held in an `AtomicReference`.

Note: the function passed to `updateAndGet` may be called several times when the CAS fails, so it must have no side effects.

## 5. Concurrent collections

| Need | Use | Do not use |
|------|-----|-----------|
| A shared map | `ConcurrentHashMap` | `HashMap` (its structure can break, it can hang), `Hashtable`, `Collections.synchronizedMap` (one lock for the whole map) |
| A list read very often, written rarely (listeners, configuration) | `CopyOnWriteArrayList` | `ArrayList` |
| A queue between threads | `BlockingQueue` | `LinkedList` |
| An ordered map | `ConcurrentSkipListMap` | `TreeMap` |
| A shared set | `ConcurrentHashMap.newKeySet()` | `HashSet` |

A `HashMap` used from several threads does not merely "lose a few entries": its internal structure can break, `get`
can return `null` for a key that is present, or iteration throws `ConcurrentModificationException`.

### The trap: compound actions on a safe collection

Demo: [CheckThenActConcurrentMapDemo.java](../demos/CheckThenActConcurrentMapDemo.java). 8 threads count page hits in a `ConcurrentHashMap`.

```java
static void recordHitBroken() {
    Integer current = hits.get(PAGE);                    // safe
    hits.put(PAGE, current == null ? 1 : current + 1);   // safe
}                                                        // combined: NOT safe
```

```
mode=broken threads=8 hitsPerThread=200,000
expected=1,600,000 actual=231,637 lost=1,368,363
RESULT: WRONG (lost updates)
```

Each call to `get` and `put` is thread-safe; the map does not break. But between `get` and `put` another thread has
already written, and this `put` overwrites it. This is exactly the read-modify-write bug of lesson 2, merely hidden
behind a class with "Concurrent" in its name.

**"Thread-safe" means each method is safe when called on its own. A sequence of calls is not.**

Fix it with the atomic compound methods `ConcurrentHashMap` provides:

```java
static void recordHitFixed() {
    hits.merge(PAGE, 1, Integer::sum);
}
```

```
expected=1,600,000 actual=1,600,000 lost=0
RESULT: CORRECT
```

| Instead of | Use |
|------------|-----|
| `if (!map.containsKey(k)) map.put(k, v)` | `map.putIfAbsent(k, v)` |
| `v = map.get(k); if (v == null) { v = create(); map.put(k, v); }` | `map.computeIfAbsent(k, key -> create())` |
| `map.put(k, map.get(k) + 1)` | `map.merge(k, 1, Integer::sum)` |
| `if (map.get(k) == old) map.put(k, neu)` | `map.replace(k, old, neu)` |

```java
ConcurrentHashMap<String, List<String>> cache = new ConcurrentHashMap<>();
cache.computeIfAbsent("k", key -> new CopyOnWriteArrayList<>()).add("v");
```

The function passed to `compute*`/`merge` runs while holding the lock of that bin: keep it short, do no I/O, and **do
not modify the same map** inside it (it can hang). The values taken out of the map must be thread-safe themselves: a
`ConcurrentHashMap<String, ArrayList>` protects the map, not each `ArrayList`.

Aggregate methods such as `size()` and `isEmpty()` on a `ConcurrentHashMap` are only estimates at one moment; do not
base decisions on them.

## 6. `BlockingQueue`: producer–consumer

A blocking queue is the standard way to hand work between threads. It gets three hard things right for you: safety,
waiting when empty, waiting when full.

```java
BlockingQueue<String> queue = new ArrayBlockingQueue<>(100);     // bounded

Thread consumer = new Thread(() -> {
    try {
        while (true) {
            String job = queue.take();          // blocks when empty
            if (job.equals("STOP")) { break; }
            System.out.println("handled " + job);
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
});
consumer.start();

queue.put("job-1");                                              // blocks when full
boolean accepted = queue.offer("job-2", 1, TimeUnit.SECONDS);    // waits at most 1 second
queue.put("STOP");
```

| Method | When full / empty |
|--------|-------------------|
| `put` / `take` | Block until possible |
| `offer(e, timeout)` / `poll(timeout)` | Wait with a deadline, return `false` / `null` |
| `offer(e)` / `poll()` | Return `false` / `null` immediately |
| `add` / `remove` | Throw an exception |

**Always bound the queue.** An unbounded queue (`new LinkedBlockingQueue<>()`) hides the fact that the consumer is
slower than the producer until memory runs out. A bounded queue creates **backpressure**: the producer is slowed down
when the system is overloaded, and that is the correct behaviour.

## 7. Choosing the tool

| Situation | Tool |
|-----------|------|
| Counters, sequence numbers | `AtomicLong` / `LongAdder` |
| On/off flags, stop flags | `volatile boolean` |
| Configuration replaced as a whole | Immutable object + `volatile` or `AtomicReference` |
| Shared caches and lookup tables | `ConcurrentHashMap` + `computeIfAbsent` |
| Handing work between threads | A bounded `BlockingQueue`, or a thread pool |
| Several variables that must stay consistent | A lock (lesson 3) |
| Per-request data | Local variables; `ThreadLocal` only if you must, always `remove()` |

## 8. Self-check

1. A singleton service has `private final Map<String, User> cache = new HashMap<>()`. Does `final` make it safe?
2. `ConcurrentHashMap<String, Integer>`: why is `map.put(k, map.get(k) + 1)` still wrong?
3. Why must the function passed to `AtomicInteger.updateAndGet` have no side effects?
4. What does an unbounded queue between a fast producer and a slow consumer lead to?

Short answers: (1) No: `final` fixes the reference only; the `HashMap` contents are still modified by several threads.
(2) `get` and `put` are two separate calls; another thread can get in between. (3) It may be called several times when
the CAS fails. (4) The queue grows without limit until `OutOfMemoryError`; the system looks fine until it falls over.

Next: [05 — Thread pools and executors](05-thread-pools-and-executors.md).
