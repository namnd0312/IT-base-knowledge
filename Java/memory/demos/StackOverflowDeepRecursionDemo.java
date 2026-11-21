/**
 * Demo 2 - StackOverflowError do de quy qua sau.
 *
 * Kich ban: dem do dai linked list bang de quy. Moi node = 1 stack frame.
 * List 1 trieu node -> stack cua thread (mac dinh ~512KB-1MB) khong du cho
 * -> java.lang.StackOverflowError.
 *
 * Luu y: day KHONG phai loi heap. Tang -Xmx khong giup gi. Stack la vung nho
 * rieng cua tung thread, chinh bang -Xss.
 *
 * Chay ban loi:  java -cp out StackOverflowDeepRecursionDemo leak
 * Chay ban fix:  java -cp out StackOverflowDeepRecursionDemo fixed
 * Thu "chua chay": java -Xss512m -cp out StackOverflowDeepRecursionDemo leak
 */
public class StackOverflowDeepRecursionDemo {

    static final class Node {
        Node next;
    }

    /** BUG: do sau de quy = do dai du lieu -> du lieu lon la tran stack. */
    static int lengthRecursive(Node node) {
        if (node == null) {
            return 0;
        }
        return 1 + lengthRecursive(node.next);
    }

    /** FIX: vong lap, dung dung 1 stack frame bat ke du lieu dai bao nhieu. */
    static int lengthIterative(Node node) {
        int length = 0;
        for (Node current = node; current != null; current = current.next) {
            length++;
        }
        return length;
    }

    public static void main(String[] args) {
        boolean fixed = args.length > 0 && args[0].equals("fixed");
        int nodeCount = Integer.getInteger("nodes", 1_000_000);

        Node head = null;
        for (int i = 0; i < nodeCount; i++) {
            Node node = new Node();
            node.next = head;
            head = node;
        }
        System.out.printf("PID=%d mode=%s nodes=%d%n",
                ProcessHandle.current().pid(), fixed ? "fixed" : "leak", nodeCount);

        int length = fixed ? lengthIterative(head) : lengthRecursive(head);
        System.out.println("DONE - length=" + length);
    }
}
