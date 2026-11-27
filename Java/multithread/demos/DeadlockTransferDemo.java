import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 * Demo 3 - Deadlock: two threads take the same two locks in opposite order.
 *
 * Scenario: thread 1 transfers A -> B, thread 2 transfers B -> A. Each locks the
 * source account first, then the target. Thread 1 holds A and waits for B while
 * thread 2 holds B and waits for A: neither can ever continue.
 *
 * Run broken:  java -cp out DeadlockTransferDemo broken
 * Run fixed:   java -cp out DeadlockTransferDemo fixed
 *
 * Option: -DhangSeconds=60 keeps the deadlocked process alive so you can run
 *         jstack <pid> or jcmd <pid> Thread.print from another terminal.
 */
public class DeadlockTransferDemo {

    static final class Account {
        final int id;
        long balance;

        Account(int id, long balance) {
            this.id = id;
            this.balance = balance;
        }
    }

    /** BUG: lock order depends on the argument order, so two callers can lock in opposite order. */
    static void transferBroken(Account from, Account to, long amount) throws InterruptedException {
        synchronized (from) {
            Thread.sleep(100); // widen the window so the deadlock happens every run
            synchronized (to) {
                from.balance -= amount;
                to.balance += amount;
            }
        }
    }

    /** FIX: always lock the account with the smaller id first - one global lock order. */
    static void transferFixed(Account from, Account to, long amount) throws InterruptedException {
        Account first = from.id < to.id ? from : to;
        Account second = from.id < to.id ? to : from;
        synchronized (first) {
            Thread.sleep(100);
            synchronized (second) {
                from.balance -= amount;
                to.balance += amount;
            }
        }
    }

    public static void main(String[] args) throws InterruptedException {
        boolean fixed = args.length > 0 && args[0].equals("fixed");
        long hangSeconds = Long.getLong("hangSeconds", 3);
        System.out.printf("PID=%d mode=%s%n", ProcessHandle.current().pid(), fixed ? "fixed" : "broken");

        Account a = new Account(1, 1000);
        Account b = new Account(2, 1000);

        Thread t1 = new Thread(() -> run(fixed, a, b, 100), "transfer-A-to-B");
        Thread t2 = new Thread(() -> run(fixed, b, a, 200), "transfer-B-to-A");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();

        t1.join(hangSeconds * 1000);
        t2.join(1000);

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long[] deadlocked = threads.findDeadlockedThreads();
        if (deadlocked == null) {
            System.out.printf("balances: A=%d B=%d total=%d%n", a.balance, b.balance, a.balance + b.balance);
            System.out.println("RESULT: CORRECT (both transfers finished)");
            return;
        }
        System.out.println("DEADLOCK detected by ThreadMXBean:");
        for (ThreadInfo info : threads.getThreadInfo(deadlocked)) {
            System.out.printf("  \"%s\" %s, waiting for lock held by \"%s\"%n",
                    info.getThreadName(), info.getThreadState(), info.getLockOwnerName());
        }
        System.out.println("RESULT: WRONG (deadlock, transfers never finish)");
    }

    private static void run(boolean fixed, Account from, Account to, long amount) {
        try {
            if (fixed) {
                transferFixed(from, to, amount);
            } else {
                transferBroken(from, to, amount);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
