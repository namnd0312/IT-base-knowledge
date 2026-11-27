import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Demo 5 - Thread pool starvation deadlock.
 *
 * Scenario: a pool of 2 threads runs 2 "order" tasks. Each order submits a
 * "payment" subtask to the SAME pool and blocks on its result. Both pool threads
 * are now waiting for subtasks that sit in the queue with no thread left to run
 * them. No lock is involved, so deadlock detectors report nothing.
 *
 * Run broken:  java -cp out ThreadPoolStarvationDeadlockDemo broken
 * Run fixed:   java -cp out ThreadPoolStarvationDeadlockDemo fixed
 *
 * Option: -DwaitSeconds=60 keeps the stuck process alive so you can take a thread dump.
 */
public class ThreadPoolStarvationDeadlockDemo {

    public static void main(String[] args) throws Exception {
        boolean fixed = args.length > 0 && args[0].equals("fixed");
        long waitSeconds = Long.getLong("waitSeconds", 3);
        System.out.printf("PID=%d mode=%s%n", ProcessHandle.current().pid(), fixed ? "fixed" : "broken");

        ExecutorService orderPool = Executors.newFixedThreadPool(2);
        // BUG: subtasks share the pool of the tasks that wait for them.
        // FIX: subtasks get their own pool, so a waiting parent never blocks its child.
        ExecutorService paymentPool = fixed ? Executors.newFixedThreadPool(2) : orderPool;

        Future<String> order1 = orderPool.submit(() -> processOrder(1, paymentPool));
        Future<String> order2 = orderPool.submit(() -> processOrder(2, paymentPool));

        try {
            System.out.println(order1.get(waitSeconds, TimeUnit.SECONDS));
            System.out.println(order2.get(waitSeconds, TimeUnit.SECONDS));
            System.out.println("RESULT: CORRECT (all orders completed)");
        } catch (TimeoutException e) {
            // toString of a ThreadPoolExecutor shows pool size, active threads and queued tasks
            System.out.println("orderPool state: " + orderPool);
            System.out.println("RESULT: WRONG (no order finished in time: every pool thread waits for a queued subtask)");
        } finally {
            orderPool.shutdownNow();
            paymentPool.shutdownNow();
        }
    }

    static String processOrder(int orderId, ExecutorService paymentPool) throws Exception {
        Future<String> payment = paymentPool.submit(() -> {
            Thread.sleep(200); // simulated call to a payment provider
            return "paid";
        });
        return "order " + orderId + " -> " + payment.get(); // blocks a pool thread
    }
}
