import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demo 1 - Race condition: lost updates on a shared counter.
 *
 * Scenario: 8 threads each increment the same counter 1,000,000 times.
 * "counter++" is three steps (read, add, write). Two threads that read the same
 * value both write value+1, so one increment is lost.
 *
 * Run broken:  java -cp out RaceConditionCounterDemo broken
 * Run fixed:   java -cp out RaceConditionCounterDemo fixed
 */
public class RaceConditionCounterDemo {

    static final int THREADS = 8;
    static final int INCREMENTS_PER_THREAD = 1_000_000;

    /** BUG: plain int, read-modify-write is not atomic. */
    static int plainCounter = 0;

    /** FIX: the increment is a single atomic operation (compare-and-swap). */
    static final AtomicInteger atomicCounter = new AtomicInteger();

    public static void main(String[] args) throws InterruptedException {
        boolean fixed = args.length > 0 && args[0].equals("fixed");
        System.out.printf(Locale.US, "mode=%s threads=%d incrementsPerThread=%,d%n",
                fixed ? "fixed" : "broken", THREADS, INCREMENTS_PER_THREAD);

        Runnable work = () -> {
            for (int i = 0; i < INCREMENTS_PER_THREAD; i++) {
                if (fixed) {
                    atomicCounter.incrementAndGet();
                } else {
                    plainCounter++;
                }
            }
        };

        Thread[] threads = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            threads[i] = new Thread(work, "worker-" + i);
            threads[i].start();
        }
        for (Thread thread : threads) {
            thread.join(); // wait for every worker before reading the result
        }

        int expected = THREADS * INCREMENTS_PER_THREAD;
        int actual = fixed ? atomicCounter.get() : plainCounter;
        System.out.printf(Locale.US, "expected=%,d actual=%,d lost=%,d%n", expected, actual, expected - actual);
        System.out.println(actual == expected ? "RESULT: CORRECT" : "RESULT: WRONG (lost updates)");
    }
}
