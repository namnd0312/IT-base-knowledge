import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Demo 4 - Check-then-act: a thread-safe collection used in a non-atomic way.
 *
 * Scenario: 8 threads count page hits in a ConcurrentHashMap. Each single call
 * (get, put) is thread-safe, but "get, add one, put" is three separate calls and
 * another thread can run between them. The map never corrupts, yet hits are lost.
 *
 * Run broken:  java -cp out CheckThenActConcurrentMapDemo broken
 * Run fixed:   java -cp out CheckThenActConcurrentMapDemo fixed
 */
public class CheckThenActConcurrentMapDemo {

    static final int THREADS = 8;
    static final int HITS_PER_THREAD = 200_000;
    static final String PAGE = "/home";

    static final ConcurrentHashMap<String, Integer> hits = new ConcurrentHashMap<>();

    /** BUG: compound action built from individually safe calls. */
    static void recordHitBroken() {
        Integer current = hits.get(PAGE);
        hits.put(PAGE, current == null ? 1 : current + 1);
    }

    /** FIX: merge performs read-modify-write atomically for that key. */
    static void recordHitFixed() {
        hits.merge(PAGE, 1, Integer::sum);
    }

    public static void main(String[] args) throws InterruptedException {
        boolean fixed = args.length > 0 && args[0].equals("fixed");
        System.out.printf(Locale.US, "mode=%s threads=%d hitsPerThread=%,d%n",
                fixed ? "fixed" : "broken", THREADS, HITS_PER_THREAD);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        for (int t = 0; t < THREADS; t++) {
            pool.execute(() -> {
                for (int i = 0; i < HITS_PER_THREAD; i++) {
                    if (fixed) {
                        recordHitFixed();
                    } else {
                        recordHitBroken();
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.MINUTES);

        int expected = THREADS * HITS_PER_THREAD;
        int actual = hits.get(PAGE);
        System.out.printf(Locale.US, "expected=%,d actual=%,d lost=%,d%n", expected, actual, expected - actual);
        System.out.println(actual == expected ? "RESULT: CORRECT" : "RESULT: WRONG (lost updates)");
    }
}
