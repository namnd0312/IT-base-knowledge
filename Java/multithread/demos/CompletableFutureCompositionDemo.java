import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Demo 6 - CompletableFuture: independent slow calls, sequential vs parallel.
 *
 * Scenario: a dashboard needs a user profile (300 ms), the order list (400 ms) and
 * recommendations (500 ms). The three calls do not depend on each other.
 *
 * Run sequential:  java -cp out CompletableFutureCompositionDemo sequential   (about 1200 ms)
 * Run parallel:    java -cp out CompletableFutureCompositionDemo parallel     (about 500 ms)
 * Run failure:     java -cp out CompletableFutureCompositionDemo failure      (one call fails, fallback used)
 */
public class CompletableFutureCompositionDemo {

    record Dashboard(String user, List<String> orders, List<String> recommendations) {
    }

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "sequential";
        System.out.println("mode=" + mode);
        long start = System.nanoTime();

        Dashboard dashboard = switch (mode) {
            case "parallel" -> loadParallel(false);
            case "failure" -> loadParallel(true);
            default -> loadSequential();
        };

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        System.out.println(dashboard);
        System.out.println("elapsed=" + elapsedMs + " ms");
    }

    /** Each call waits for the previous one: total = 300 + 400 + 500. */
    static Dashboard loadSequential() {
        return new Dashboard(fetchUser(), fetchOrders(), fetchRecommendations(false));
    }

    /** All three start at once: total = the slowest call. */
    static Dashboard loadParallel(boolean recommendationsFail) {
        // A dedicated pool for blocking calls; the common ForkJoinPool is meant for CPU work.
        ExecutorService ioPool = Executors.newFixedThreadPool(3);
        try {
            CompletableFuture<String> user =
                    CompletableFuture.supplyAsync(CompletableFutureCompositionDemo::fetchUser, ioPool);
            CompletableFuture<List<String>> orders =
                    CompletableFuture.supplyAsync(CompletableFutureCompositionDemo::fetchOrders, ioPool);
            CompletableFuture<List<String>> recommendations =
                    CompletableFuture.supplyAsync(() -> fetchRecommendations(recommendationsFail), ioPool)
                            .orTimeout(2, TimeUnit.SECONDS)
                            // An optional part must not break the whole page: log and fall back.
                            .exceptionally(error -> {
                                System.out.println("recommendations failed: " + error.getMessage() + " -> using fallback");
                                return List.of("bestseller-1");
                            });

            return user
                    .thenCombine(orders, (u, o) -> new Dashboard(u, o, List.of()))
                    .thenCombine(recommendations, (d, r) -> new Dashboard(d.user(), d.orders(), r))
                    .join(); // block once, at the edge, after everything is composed
        } finally {
            ioPool.shutdown();
        }
    }

    static String fetchUser() {
        sleep(300);
        return "an";
    }

    static List<String> fetchOrders() {
        sleep(400);
        return List.of("order-1", "order-2");
    }

    static List<String> fetchRecommendations(boolean fail) {
        sleep(500);
        if (fail) {
            throw new IllegalStateException("recommendation service is down");
        }
        return List.of("book-7", "book-9");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
