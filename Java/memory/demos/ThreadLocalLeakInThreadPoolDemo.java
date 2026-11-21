import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Demo 4 - Leak qua ThreadLocal trong thread pool.
 *
 * Kich ban: moi request gom du lieu tam vao 1 ThreadLocal (kieu "request context").
 * Thread trong pool KHONG chet sau khi xong request -> gia tri ThreadLocal song tiep
 * sang request sau. Khong goi remove() -> buffer cua moi thread phinh mai
 * -> java.lang.OutOfMemoryError: Java heap space.
 *
 * Dau hieu trong heap dump: duong toi GC root di qua
 *   Thread -> threadLocals (ThreadLocalMap) -> Entry.value -> ArrayList -> byte[]
 *
 * Chay ban loi:
 *   java -Xmx64m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=dumps \
 *        -cp out ThreadLocalLeakInThreadPoolDemo leak
 * Chay ban fix:
 *   java -Xmx64m -cp out ThreadLocalLeakInThreadPoolDemo fixed
 */
public class ThreadLocalLeakInThreadPoolDemo {

    /** Du lieu tam cua 1 request, gan vao thread dang xu ly. */
    static final ThreadLocal<List<byte[]>> REQUEST_BUFFER = ThreadLocal.withInitial(ArrayList::new);

    /** BUG: xong request khong don ThreadLocal -> du lieu ton tai theo vong doi cua thread. */
    static void handleRequestLeaky() {
        REQUEST_BUFFER.get().add(new byte[100 * 1024]);
    }

    /** FIX: luon remove() trong finally, ke ca khi request nem exception. */
    static void handleRequestFixed() {
        try {
            REQUEST_BUFFER.get().add(new byte[100 * 1024]);
        } finally {
            REQUEST_BUFFER.remove();
        }
    }

    public static void main(String[] args) throws InterruptedException {
        boolean fixed = args.length > 0 && args[0].equals("fixed");
        long pauseMs = Long.getLong("pauseMs", 20);
        int totalRequests = fixed ? 1500 : Integer.MAX_VALUE;
        ExecutorService pool = Executors.newFixedThreadPool(4);

        System.out.printf("PID=%d mode=%s maxHeap=%dMB%n",
                ProcessHandle.current().pid(), fixed ? "fixed" : "leak",
                Runtime.getRuntime().maxMemory() / (1024 * 1024));

        try {
            for (int i = 1; i <= totalRequests; i++) {
                Runnable request = fixed
                        ? ThreadLocalLeakInThreadPoolDemo::handleRequestFixed
                        : ThreadLocalLeakInThreadPoolDemo::handleRequestLeaky;
                pool.submit(request).get();
                if (i % 100 == 0) {
                    Runtime rt = Runtime.getRuntime();
                    long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                    System.out.printf("requests=%d heapUsed=%dMB%n", i, usedMb);
                }
                Thread.sleep(pauseMs);
            }
            System.out.println("DONE - heap on dinh, khong OOM");
        } catch (ExecutionException e) {
            // Loi trong worker thread duoc boc trong ExecutionException.
            e.getCause().printStackTrace();
            System.exit(1);
        } finally {
            pool.shutdown();
        }
    }
}
