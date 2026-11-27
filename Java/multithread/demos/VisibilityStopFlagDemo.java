import java.util.Locale;

/**
 * Demo 2 - Visibility: a thread never sees a value written by another thread.
 *
 * Scenario: a worker spins while a "running" flag is true. Main sets the flag to
 * false after one second. Without volatile, the JIT is allowed to read the flag
 * once and hoist it out of the loop, so the worker spins forever.
 *
 * Run broken:  java -cp out VisibilityStopFlagDemo broken
 * Run fixed:   java -cp out VisibilityStopFlagDemo fixed
 */
public class VisibilityStopFlagDemo {

    /** BUG: no happens-before between the write in main and the read in the worker. */
    static boolean plainRunning = true;

    /** FIX: volatile guarantees every read sees the latest write. */
    static volatile boolean volatileRunning = true;

    public static void main(String[] args) throws InterruptedException {
        boolean fixed = args.length > 0 && args[0].equals("fixed");
        System.out.println("mode=" + (fixed ? "fixed" : "broken"));

        Thread worker = new Thread(() -> {
            long iterations = 0;
            if (fixed) {
                while (volatileRunning) {
                    iterations++;
                }
            } else {
                while (plainRunning) {
                    iterations++;
                }
            }
            System.out.printf(Locale.US, "worker: saw the stop flag after %,d iterations%n", iterations);
        }, "spinner");
        worker.setDaemon(true); // lets the JVM exit even if the worker never stops
        worker.start();

        Thread.sleep(1000); // long enough for the JIT to compile the hot loop
        if (fixed) {
            volatileRunning = false;
        } else {
            plainRunning = false;
        }
        System.out.println("main: set running=false");

        worker.join(3000);
        if (worker.isAlive()) {
            System.out.println("RESULT: WRONG (worker is still spinning 3s after the flag changed)");
        } else {
            System.out.println("RESULT: CORRECT (worker stopped)");
        }
    }
}
