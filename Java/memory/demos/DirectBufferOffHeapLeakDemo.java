import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Demo 5 - Leak bo nho ngoai heap (direct buffer).
 *
 * Kich ban: moi "ket noi" cap 1 direct buffer 1MB (ByteBuffer.allocateDirect) va giu lai
 * trong list, dong ket noi khong tra buffer. Direct buffer nam o native memory:
 * heapUsed gan nhu khong tang nhung RSS cua process tang deu
 * -> java.lang.OutOfMemoryError: Cannot reserve ... bytes of direct buffer memory.
 *
 * Bai hoc: heap nho khong co nghia process nho. Phai nhin ca native memory
 * (BufferPoolMXBean, Native Memory Tracking).
 *
 * Chay ban loi:
 *   java -Xmx64m -XX:MaxDirectMemorySize=64m -XX:NativeMemoryTracking=summary \
 *        -cp out DirectBufferOffHeapLeakDemo leak
 * Chay ban fix:
 *   java -Xmx64m -XX:MaxDirectMemorySize=64m -cp out DirectBufferOffHeapLeakDemo fixed
 */
public class DirectBufferOffHeapLeakDemo {

    static final int BUFFER_SIZE = 1024 * 1024;

    /** BUG: buffer cua ket noi da dong van bi giu -> native memory khong bao gio duoc tra. */
    static final List<ByteBuffer> LEAKED_CONNECTION_BUFFERS = new ArrayList<>();

    /** FIX: 1 buffer dung lai cho moi ket noi (ung dung that: buffer pool co gioi han). */
    static final ByteBuffer REUSABLE_BUFFER = ByteBuffer.allocateDirect(BUFFER_SIZE);

    static void handleConnectionLeaky() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(BUFFER_SIZE);
        buffer.putLong(System.nanoTime());
        LEAKED_CONNECTION_BUFFERS.add(buffer);
    }

    static void handleConnectionFixed() {
        REUSABLE_BUFFER.clear();
        REUSABLE_BUFFER.putLong(System.nanoTime());
    }

    public static void main(String[] args) throws InterruptedException {
        boolean fixed = args.length > 0 && args[0].equals("fixed");
        long pauseMs = Long.getLong("pauseMs", 200);
        int totalConnections = fixed ? 200 : Integer.MAX_VALUE;

        System.out.printf("PID=%d mode=%s%n", ProcessHandle.current().pid(), fixed ? "fixed" : "leak");

        for (int i = 1; i <= totalConnections; i++) {
            if (fixed) {
                handleConnectionFixed();
            } else {
                handleConnectionLeaky();
            }
            if (i % 10 == 0) {
                Runtime rt = Runtime.getRuntime();
                long heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                System.out.printf("connections=%d heapUsed=%dMB directUsed=%dMB%n",
                        i, heapUsedMb, directMemoryUsedBytes() / (1024 * 1024));
            }
            Thread.sleep(pauseMs);
        }
        System.out.println("DONE - direct memory on dinh, khong OOM");
    }

    private static long directMemoryUsedBytes() {
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if (pool.getName().equals("direct")) {
                return pool.getMemoryUsed();
            }
        }
        return -1;
    }
}
