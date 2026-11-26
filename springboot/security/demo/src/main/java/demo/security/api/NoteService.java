package demo.security.api;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * Phan quyen o tang method (method security).
 *
 * Luat theo URL khong tra loi duoc cau "user nay co phai chu cua ban ghi nay khong".
 * Loai kiem tra theo du lieu do phai dat o tang service.
 */
@Service
public class NoteService {

    public record Note(long id, String owner, String content) {
    }

    private final Map<Long, Note> notes = new ConcurrentHashMap<>(Map.of(
            1L, new Note(1, "user", "Ghi chu rieng cua user"),
            2L, new Note(2, "admin", "Ghi chu rieng cua admin")));

    /**
     * @PostAuthorize chay SAU khi method tra ve, nen doc duoc returnObject.
     * Chi chu ghi chu hoac ADMIN moi nhan duoc ket qua; nguoi khac -> 403.
     * Chi dung cho method KHONG co tac dung phu (method da chay xong roi moi kiem tra).
     */
    @PostAuthorize("returnObject.owner == authentication.name or hasRole('ADMIN')")
    public Note findById(long id) {
        Note note = notes.get(id);
        if (note == null) {
            throw new NoSuchElementException("Khong tim thay ghi chu " + id);
        }
        return note;
    }

    /** @PreAuthorize chay TRUOC method: khong du quyen thi method khong duoc goi. */
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(long id) {
        notes.remove(id);
    }
}
