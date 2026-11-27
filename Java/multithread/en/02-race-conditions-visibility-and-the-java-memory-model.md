# 02 — Race conditions, visibility and the Java Memory Model

Goal: after this lesson you can name the three root failures of concurrent code, understand why they happen at the
CPU and JVM level, and know what `volatile` does and does not solve.

## 1. How concurrency bugs differ from ordinary bugs

| | Ordinary bug | Concurrency bug |
|---|---|---|
| Reproduction | Same input, same result | Sometimes yes, sometimes no; timing-dependent |
| Tests | Catch it | Green thousands of times, then wrong in production |
| Debugging | A breakpoint shows it | A breakpoint changes the timing and the bug disappears |
| Symptom | A clear exception | Silently wrong numbers, hangs, rarely an exception |

So concurrent code **must be right by design**. You cannot "test until it fails, then fix".

Every such bug comes down to one condition: **there is mutable state that more than one thread accesses, and at least
one thread writes**. Remove any one of the three ingredients (shared, mutable, written) and the bug is gone; this is
the basis of the strategies in lesson 4.

There are three ways to break: **atomicity**, **visibility**, **ordering**.

## 2. Atomicity: an operation is interleaved

Demo: [RaceConditionCounterDemo.java](../demos/RaceConditionCounterDemo.java). 8 threads each add 1 million times to a shared variable.

```java
static int plainCounter = 0;
...
plainCounter++;
```

```
mode=broken threads=8 incrementsPerThread=1,000,000
expected=8,000,000 actual=1,963,442 lost=6,036,558
RESULT: WRONG (lost updates)
```

More than 6 million additions lost, and no exception. The reason: `counter++` looks like one step but is three:

```
1. read the value of counter into a register
2. add 1
3. write the result back to counter
```

Two threads interleave:

```
Thread A                    Thread B                 counter
read 41                                                41
                            read 41                    41
add → 42                                               41
                            add → 42                   41
write 42                                               42
                            write 42                   42   ← two additions, only +1
```

This is a **race condition**: the result depends on the accidental ordering of the threads. Two common shapes:

| Shape | Example | Why it is wrong |
|-------|---------|-----------------|
| Read-modify-write | `count++`, `balance -= amount` | The value read is stale by the time it is written |
| Check-then-act | `if (!map.containsKey(k)) map.put(k, v)`, `if (instance == null) instance = new ...` | The checked condition no longer holds when acting |

Fix it by making the whole sequence **atomic** (nobody can get in between): use a lock (lesson 3) or an atomic class
(lesson 4). The demo's fix:

```java
static final AtomicInteger atomicCounter = new AtomicInteger();
...
atomicCounter.incrementAndGet();
```

```
expected=8,000,000 actual=8,000,000 lost=0
RESULT: CORRECT
```

## 3. Visibility: written, but the other thread does not see it

Demo: [VisibilityStopFlagDemo.java](../demos/VisibilityStopFlagDemo.java). A worker spins until the `running` flag becomes `false`.

```java
static boolean plainRunning = true;

// worker
while (plainRunning) {
    iterations++;
}

// main, after 1 second
plainRunning = false;
```

```
mode=broken
main: set running=false
RESULT: WRONG (worker is still spinning 3s after the flag changed)
```

`main` wrote `false`, yet the worker spins forever. No operation is interleaved here; the problem is that the worker
**never re-reads** the variable. Two causes stack up:

- **Hardware**: every core has its own cache. A value written on one core does not instantly appear on another.
- **JIT**: the compiler sees that nothing in the loop writes `plainRunning`, and the specification does not require it
  to consider other threads. It reads the variable once and turns the loop into `while (true)`.

The worker's thread dump at that moment: `RUNNABLE`, consuming a whole core, stuck on the loop line.

```
"spinner" #20 daemon prio=5 cpu=2169.71ms elapsed=2.17s ... runnable
   java.lang.Thread.State: RUNNABLE
	at VisibilityStopFlagDemo.lambda$main$0(VisibilityStopFlagDemo.java:33)
```

This bug is especially nasty because it only shows once the JIT has optimised the loop: run it in a debugger or add a
`System.out.println` inside the loop and it "goes away". Do not mistake that for a fix.

Fix it with `volatile`:

```java
static volatile boolean volatileRunning = true;
```

```
mode=fixed
main: set running=false
worker: saw the stop flag after 1,753,059,506 iterations
RESULT: CORRECT (worker stopped)
```

## 4. Ordering: instructions get reordered

The compiler and the CPU may reorder instructions as long as the result **within one thread** is unchanged. Another
thread may then observe an intermediate state that "cannot happen" according to the source code.

The classic example is broken double-checked locking:

```java
class Config {
    private static Config instance;            // WRONG: volatile is missing

    static Config get() {
        if (instance == null) {                // (1) unlocked check
            synchronized (Config.class) {
                if (instance == null) {
                    instance = new Config();   // (2)
                }
            }
        }
        return instance;
    }
}
```

`instance = new Config()` consists of: allocate memory, run the constructor, assign the reference. If the assignment
is ordered before the constructor finishes, another thread at step (1) sees `instance != null` and uses an object
that is **not fully initialised**.

Fix: declare `private static volatile Config instance`. Better still, avoid the pattern altogether:

```java
class Config {
    private Config() {}
    private static class Holder { static final Config INSTANCE = new Config(); }
    static Config get() { return Holder.INSTANCE; }   // the JVM guarantees safe, lazy class initialisation
}
```

## 5. The Java Memory Model and happens-before

The Java Memory Model (JMM) is the part of the specification that answers: **when is a thread guaranteed to see a
value written by another thread?**

The answer: when a **happens-before** relationship exists between the write and the read. Without it, the JVM is
allowed to let the reader see the old value, indefinitely.

The happens-before rules worth knowing:

| Rule | Content |
|------|---------|
| Within one thread | An earlier statement happens-before a later one |
| Locks | Releasing a lock happens-before the next acquisition of **that same lock** |
| `volatile` | A write to a volatile variable happens-before every later read of it |
| Thread start | `thread.start()` happens-before every statement in that thread |
| Thread termination | Every statement in a thread happens-before `thread.join()` returning |
| `final` | A `final` field assigned in the constructor is seen correctly by every thread, provided `this` does not escape during construction |
| Transitivity | A before B and B before C means A before C |

Practical consequences:

- Reads and writes of the same shared variable must go through **one** synchronisation mechanism: the same lock, or
  `volatile`, or an atomic. Synchronising only the writer is not enough.
- `synchronized` solves both atomicity and visibility. That is why the getter of a lock-protected field must be
  `synchronized` too.
- A result computed by a thread and read after `join()` is safe without `volatile`. Demo 1 reads `plainCounter` after
  `join()` by exactly this rule.

## 6. `volatile`: what it does and does not do

| | `volatile` | `synchronized` / `Lock` | Atomics |
|---|---|---|---|
| Visibility | Yes | Yes | Yes |
| Prevents reordering | Yes | Yes | Yes |
| Atomicity of compound actions (`++`, check-then-act) | **No** | Yes | Yes (within one variable) |
| Blocks threads | No | Yes | No |

`volatile int counter; counter++` **still loses updates**: every read and every write "see each other", but the three
steps of `++` are still interleaved.

`volatile` is right when both conditions hold:

1. The value written does not depend on the old value (a plain assignment such as `flag = false`,
   `config = newConfig`), or only one thread writes.
2. The variable does not take part in an invariant with another variable (for example `lower <= upper`).

Typical cases: a stop flag, a status flag, a reference to an immutable object that is replaced as a whole.

## 7. Diagnosis summary

| Symptom | Failure type | Fix direction |
|---------|--------------|---------------|
| Counters or balances drift under load | Atomicity (read-modify-write) | Lock or atomic |
| Occasional duplicates, double initialisation | Atomicity (check-then-act) | Lock, `computeIfAbsent`, `putIfAbsent` |
| A thread does not stop, does not pick up new configuration | Visibility | `volatile`, or read under the lock |
| The bug vanishes when debugging or adding logs | Visibility or ordering | As above; do not trust "it went away" |
| An object is seen half-built | Ordering, unsafe publication | `final`, `volatile`, do not leak `this` from the constructor |

## 8. Self-check

1. Does `volatile int count; count++` from 8 threads give the right result? Why?
2. In the visibility demo, adding `System.out.println` inside the loop makes the program stop. Is the bug gone?
3. Thread A writes `x = 5` inside `synchronized (lock)`. Thread B reads `x` without the lock. Is B guaranteed to see 5?
4. Why is reading `plainCounter` after `join()`ing every worker safe even though the variable is not `volatile`?

Short answers: (1) No: `volatile` does not make `++` atomic. (2) No: `println` takes a lock internally and prevents
the JIT from optimising the loop; the bug is only hidden. (3) No: the lock's happens-before only applies to threads
that take **the same lock**. (4) Because `join()` returning creates a happens-before with every statement of the
finished thread.

Next: [03 — synchronized, locks and deadlock](03-synchronized-locks-and-deadlock.md).
