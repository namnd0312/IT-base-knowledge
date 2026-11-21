import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Demo 1 - Heap leak do cache static khong gioi han kich thuoc.
 *
 * Kich ban: moi request "dang nhap" tao 1 UserSession va nhet vao cache static.
 * Khong ai xoa -> GC khong thu hoi duoc (static field la GC root) -> heap day dan
 * -> java.lang.OutOfMemoryError: Java heap space.
 *
 * Chay ban loi:
 *   java -Xmx64m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=dumps \
 *        -cp out HeapLeakUnboundedCacheDemo leak
 * Chay ban fix:
 *   java -Xmx64m -cp out HeapLeakUnboundedCacheDemo fixed
 *
 * Tuy chon: -DpauseMs=20 (nghi giua 2 request, tang len de co thoi gian attach jcmd).
 */
public class HeapLeakUnboundedCacheDemo {

    /** Class nghiep vu rieng de de nhan ra trong class histogram / heap dump. */
    static final class UserSession {
        final String userId;
        final byte[] profileData = new byte[100 * 1024]; // ~100 KB moi session

        UserSession(String userId) {
            this.userId = userId;
        }
    }

    /** BUG: chi put, khong bao gio remove -> lon mai theo so user. */
    static final Map<String, UserSession> LEAKY_CACHE = new HashMap<>();

    static final int MAX_CACHED_SESSIONS = 100;

    /** FIX: LRU cache co tran, tu loai phan tu lau khong dung nhat. */
    static final Map<String, UserSession> BOUNDED_CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, UserSession> eldest) {
                    return size() > MAX_CACHED_SESSIONS;
                }
            };

    public static void main(String[] args) throws InterruptedException {
        boolean fixed = args.length > 0 && args[0].equals("fixed");
        Map<String, UserSession> cache = fixed ? BOUNDED_CACHE : LEAKY_CACHE;
        long pauseMs = Long.getLong("pauseMs", 20);
        int totalRequests = fixed ? 1500 : Integer.MAX_VALUE;

        System.out.printf("PID=%d mode=%s maxHeap=%dMB%n",
                ProcessHandle.current().pid(), fixed ? "fixed" : "leak",
                Runtime.getRuntime().maxMemory() / (1024 * 1024));

        for (int i = 1; i <= totalRequests; i++) {
            String userId = "user-" + i;
            cache.put(userId, new UserSession(userId));
            if (i % 100 == 0) {
                Runtime rt = Runtime.getRuntime();
                long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                System.out.printf("requests=%d cacheSize=%d heapUsed=%dMB%n", i, cache.size(), usedMb);
            }
            Thread.sleep(pauseMs);
        }
        System.out.println("DONE - heap on dinh, khong OOM");
    }
}
