import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Demo 8 - Virtual threads (JDK 21): many blocking tasks, platform pool vs virtual threads.
 *
 * Scenario: 2,000 requests each block for 200 ms on I/O (simulated with sleep).
 * A pool of 100 platform threads can only run 100 at a time: 20 waves x 200 ms = about 4 s.
 * With one virtual thread per task, a blocked task releases its carrier thread, so all
 * 2,000 wait at the same time: about 200 ms in total.
 *
 * Run platform:  java -cp out VirtualThreadsBlockingIoDemo platform
 * Run virtual:   java -cp out VirtualThreadsBlockingIoDemo virtual
 */
public class VirtualThreadsBlockingIoDemo {

    static final int TASKS = 2_000;
    static final int PLATFORM_POOL_SIZE = 100;
    static final long BLOCK_MILLIS = 200;

    public static void main(String[] args) throws InterruptedException {
        boolean virtual = args.length > 0 && args[0].equals("virtual");
        System.out.printf(Locale.US, "mode=%s tasks=%,d blockMillis=%d%n",
                virtual ? "virtual" : "platform(pool=" + PLATFORM_POOL_SIZE + ")", TASKS, BLOCK_MILLIS);

        AtomicInteger completed = new AtomicInteger();
        long start = System.nanoTime();

        // ExecutorService is AutoCloseable since JDK 19: close() waits for submitted tasks.
        try (ExecutorService executor = virtual
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newFixedThreadPool(PLATFORM_POOL_SIZE)) {
            for (int i = 0; i < TASKS; i++) {
                executor.execute(() -> {
                    try {
                        Thread.sleep(BLOCK_MILLIS); // blocking call: DB query, HTTP request...
                        completed.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
        }

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        System.out.printf(Locale.US, "completed=%,d elapsed=%d ms%n", completed.get(), elapsedMs);
    }
}
