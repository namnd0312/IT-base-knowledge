import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Demo 3 - Metaspace leak do ClassLoader bi giu lai.
 *
 * Kich ban: he thong "plugin" moi lan goi lai tao 1 ClassLoader moi de nap lai class
 * (kieu hot-reload), nhung van giu Class cu trong 1 registry static.
 * Class chi duoc go khoi Metaspace khi ClassLoader cua no khong con ai tham chieu
 * -> moi lan reload la 1 ban sao class moi nam lai mai mai
 * -> java.lang.OutOfMemoryError: Metaspace.
 *
 * Luu y: Metaspace nam NGOAI heap. -Xmx khong lien quan, heap dump van huu ich
 * vi no cho thay ClassLoader nao dang bi giu.
 *
 * Chay ban loi:
 *   java -XX:MaxMetaspaceSize=32m -cp out MetaspaceClassLoaderLeakDemo leak
 * Chay ban fix:
 *   java -XX:MaxMetaspaceSize=32m -cp out MetaspaceClassLoaderLeakDemo fixed
 */
public class MetaspaceClassLoaderLeakDemo {

    /** Class se bi nap di nap lai boi nhieu ClassLoader khac nhau. */
    public static class Plugin {
        public String run() {
            return "ok";
        }
    }

    /** Moi instance tu dinh nghia 1 ban sao rieng cua class Plugin. */
    static final class PluginClassLoader extends ClassLoader {
        Class<?> definePlugin(byte[] classBytes) {
            return defineClass(Plugin.class.getName(), classBytes, 0, classBytes.length);
        }
    }

    /** BUG: giu moi phien ban class da nap -> ClassLoader tuong ung khong bao gio duoc GC. */
    static final List<Class<?>> LEAKY_PLUGIN_REGISTRY = new ArrayList<>();

    /** FIX: moi plugin chi nap 1 lan, dung lai Class da nap. */
    static final Map<String, Class<?>> PLUGIN_CACHE = new HashMap<>();

    static Class<?> loadPluginLeaky(byte[] classBytes) {
        Class<?> pluginClass = new PluginClassLoader().definePlugin(classBytes);
        LEAKY_PLUGIN_REGISTRY.add(pluginClass);
        return pluginClass;
    }

    static Class<?> loadPluginFixed(String pluginName, byte[] classBytes) {
        return PLUGIN_CACHE.computeIfAbsent(pluginName,
                name -> new PluginClassLoader().definePlugin(classBytes));
    }

    public static void main(String[] args) throws Exception {
        boolean fixed = args.length > 0 && args[0].equals("fixed");
        long pauseMs = Long.getLong("pauseMs", 2);
        int totalCalls = fixed ? 20_000 : Integer.MAX_VALUE;
        byte[] classBytes = readPluginClassBytes();

        System.out.printf("PID=%d mode=%s%n", ProcessHandle.current().pid(), fixed ? "fixed" : "leak");

        for (int i = 1; i <= totalCalls; i++) {
            Class<?> pluginClass = fixed
                    ? loadPluginFixed("report-plugin", classBytes)
                    : loadPluginLeaky(classBytes);
            pluginClass.getDeclaredConstructor().newInstance();
            if (i % 1000 == 0) {
                System.out.printf("calls=%d loadedClasses=%d metaspaceCommitted=%dMB%n", i,
                        ManagementFactory.getClassLoadingMXBean().getLoadedClassCount(),
                        metaspaceCommittedBytes() / (1024 * 1024));
            }
            Thread.sleep(pauseMs);
        }
        System.out.println("DONE - metaspace on dinh, khong OOM");
    }

    private static byte[] readPluginClassBytes() throws IOException {
        String resource = Plugin.class.getName().replace('.', '/') + ".class";
        try (InputStream in = MetaspaceClassLoaderLeakDemo.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Khong tim thay " + resource + " - hay bien dich bang javac -d out truoc");
            }
            return in.readAllBytes();
        }
    }

    private static long metaspaceCommittedBytes() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getName().equals("Metaspace")) {
                return pool.getUsage().getCommitted();
            }
        }
        return -1;
    }
}
