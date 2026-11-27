import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demo 7 - Synchronizers: CountDownLatch as a start gate, Semaphore as a concurrency limit.
 *
 * Scenario: 20 tasks call a downstream service that can only handle 3 calls at a time.
 * A CountDownLatch releases all tasks at the same instant to create a burst.
 * Without a limit all 20 hit the service together; a Semaphore(3) caps it at 3.
 *
 * Run broken:  java -cp out SemaphoreConcurrencyLimitDemo broken
 * Run fixed:   java -cp out SemaphoreConcurrencyLimitDemo fixed
 */
public class SemaphoreConcurrencyLimitDemo {

    static final int TASKS = 20;
    static final int DOWNSTREAM_CAPACITY = 3;

    static final AtomicInteger inFlight = new AtomicInteger();
    static final AtomicInteger peakInFlight = new AtomicInteger();

    public static void main(String[] args) throws InterruptedException {
        boolean fixed = args.length > 0 && args[0].equals("fixed");
        System.out.printf("mode=%s tasks=%d downstreamCapacity=%d%n",
                fixed ? "fixed" : "broken", TASKS, DOWNSTREAM_CAPACITY);

        Semaphore permits = new Semaphore(DOWNSTREAM_CAPACITY);
        CountDownLatch startGate = new CountDownLatch(1);      // one count: opened once by main
        CountDownLatch allDone = new CountDownLatch(TASKS);    // one count per task
        ExecutorService pool = Executors.newFixedThreadPool(TASKS);
        long start = System.currentTimeMillis();

        for (int i = 0; i < TASKS; i++) {
            pool.execute(() -> {
                try {
                    startGate.await(); // every task waits here until main opens the gate
                    if (fixed) {
                        permits.acquire(); // blocks while 3 calls are already in flight
                    }
                    try {
                        callDownstream();
                    } finally {
                        if (fixed) {
                            permits.release(); // always release in finally, or permits leak
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    allDone.countDown();
                }
            });
        }

        startGate.countDown(); // open the gate: all 20 tasks start together
        allDone.await();       // wait until every task has counted down
        pool.shutdown();

        long elapsed = System.currentTimeMillis() - start;
        int peak = peakInFlight.get();
        System.out.printf("peakConcurrentCalls=%d elapsed=%d ms%n", peak, elapsed);
        System.out.println(peak <= DOWNSTREAM_CAPACITY
                ? "RESULT: CORRECT (downstream never saw more than its capacity)"
                : "RESULT: WRONG (downstream overloaded)");
    }

    static void callDownstream() throws InterruptedException {
        int now = inFlight.incrementAndGet();
        peakInFlight.accumulateAndGet(now, Math::max);
        Thread.sleep(100); // simulated remote call
        inFlight.decrementAndGet();
    }
}
