package cs_orgchart.access;

import cs_orgchart.employee.override.AdminEditException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The admin list (app_admins) for super admins (U1): list, add, remove. Names are stored as typed and compared
 * ignoring case; at most {@value #MAX_ENABLED} enabled admins. Every change empties the {@link AdminAccess} cache
 * after commit and is logged (who added or removed whom).
 */
@Slf4j
@Service
public class AppAdminService {

    static final int MAX_ENABLED = 50;
    static final int NAME_MIN = 3;
    static final int NAME_MAX = 256;
    static final int NOTE_MAX = 256;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._@+-]+");

    private final AppAdminRepository admins;
    private final AdminAccess access;

    public AppAdminService(AppAdminRepository admins, AdminAccess access) {
        this.admins = admins;
        this.access = access;
    }

    public record AdminView(Long id, String loginName, String note, String addedBy, Instant addedAt, boolean enabled) {
        static AdminView of(AppAdmin admin) {
            return new AdminView(admin.getId(), admin.getLoginName(), admin.getNote(), admin.getAddedBy(),
                    admin.getAddedAt(), admin.isEnabled());
        }
    }

    public record AddAdminRequest(String loginName, String note) {
    }

    @Transactional(readOnly = true)
    public List<AdminView> list() {
        return admins.findAllByOrderByLoginNameAsc().stream().map(AdminView::of).toList();
    }

    @Transactional
    public AdminView add(AddAdminRequest request, String by) {
        String name = request == null || request.loginName() == null ? "" : request.loginName().trim();
        if (name.length() < NAME_MIN || name.length() > NAME_MAX) {
            throw AdminEditException.badRequest("LOGIN_NAME_LENGTH",
                    "The login name must have " + NAME_MIN + " to " + NAME_MAX + " characters");
        }
        if (!NAME.matcher(name).matches()) {
            throw AdminEditException.badRequest("LOGIN_NAME_CHARACTERS",
                    "The login name may contain only letters A-Z, digits and . _ @ + -");
        }
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        if (note != null && note.length() > NOTE_MAX) {
            throw AdminEditException.badRequest("NOTE_LENGTH", "The note has more than " + NOTE_MAX + " characters");
        }
        if (note != null && note.chars().anyMatch(c -> c == '<' || c == '>' || Character.isISOControl(c))) {
            throw AdminEditException.badRequest("NOTE_CHARACTERS", "The note must not contain < > or control characters");
        }

        AppAdmin admin = admins.findByLoginNameIgnoreCase(name).orElse(null);
        if (admin != null && admin.isEnabled()) {
            throw AdminEditException.conflict("ADMIN_EXISTS", "This person is already an admin");
        }
        if (admins.countByEnabledTrue() >= MAX_ENABLED) {
            throw AdminEditException.conflict("ADMIN_LIMIT", "There are already " + MAX_ENABLED + " admins");
        }
        if (admin == null) {
            admin = new AppAdmin();
            admin.setLoginName(name);
        }
        admin.setEnabled(true);
        admin.setNote(note);
        admin.setAddedBy(by);
        admin.setAddedAt(Instant.now());
        AppAdmin saved = admins.saveAndFlush(admin);
        log.info("Admin access granted: admin id={} login={} by={}", saved.getId(), saved.getLoginName(), by);
        evictAfterCommit();
        return AdminView.of(saved);
    }

    @Transactional
    public void remove(long id, String by) {
        AppAdmin admin = admins.findById(id)
                .orElseThrow(() -> AdminEditException.notFound("ADMIN_NOT_FOUND", "No admin with this id"));
        admins.delete(admin);
        admins.flush();
        log.info("Admin access removed: admin id={} login={} by={}", id, admin.getLoginName(), by);
        evictAfterCommit();
    }

    private void evictAfterCommit() {
        access.evict();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    access.evict();
                }
            });
        }
    }
}
