package cs_orgchart.employee.visibility;

import cs_orgchart.employee.Employee;
import cs_orgchart.employee.EmployeeRepository;
import cs_orgchart.employee.override.AdminEditException;
import cs_orgchart.employee.override.AdminGuards;
import cs_orgchart.employee.override.EmployeeOverride;
import cs_orgchart.employee.override.EmployeeOverrideRepository;
import cs_orgchart.employee.override.FieldRules;
import cs_orgchart.employee.override.OverlayChanged;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Hiding people from the site (U4): global, by admins, restored by admins, never changed by the AD sync. A hidden
 * person is in no tree, group, search or counselee list; their counselees are shown under the next visible manager.
 * A hidden person cannot be a head: hiding a head takes them off the head list of every function (with a warning).
 */
@Slf4j
@Service
public class VisibilityService {

    public record HideRequest(String reason) {
    }

    public record HideResult(Long employeeId, boolean hidden, List<String> warnings) {
    }

    public record HiddenView(Long employeeId, String name, String email, String jobTitle, String hiddenBy,
                             Instant hiddenAt, String reason) {
    }

    private final EmployeeVisibilityRepository visibility;
    private final EmployeeRepository employees;
    private final EmployeeOverrideRepository overlay;
    private final AdminGuards guards;
    private final ApplicationEventPublisher events;

    public VisibilityService(EmployeeVisibilityRepository visibility, EmployeeRepository employees,
                             EmployeeOverrideRepository overlay, AdminGuards guards, ApplicationEventPublisher events) {
        this.visibility = visibility;
        this.employees = employees;
        this.overlay = overlay;
        this.guards = guards;
        this.events = events;
    }

    @Transactional
    public HideResult hide(long employeeId, HideRequest request, String admin) {
        guards.requireEditing();
        guards.requireEmployee(employeeId);
        FieldRules.Errors errors = new FieldRules.Errors();
        String reason = errors.apply(FieldRules.HIDE_REASON, request == null ? null : request.reason());
        errors.throwIfAny();
        if (guards.isHidden(employeeId)) {
            throw AdminEditException.conflict("ALREADY_HIDDEN", "This person is already hidden.");
        }
        List<String> warnings = new ArrayList<>();
        for (EmployeeOverride row : overlay.findByEmployeeId(employeeId)) {
            if (row.getHeadRank() != null) {
                dropHead(row, admin);
                warnings.add("No longer a head of " + row.getCs() + ".");
            }
        }
        visibility.saveAndFlush(EmployeeVisibility.builder().employeeId(employeeId).hidden(true).hiddenBy(admin)
                .hiddenAt(Instant.now()).reason(reason).build());
        log.info("Admin {} hid employee {} (heads dropped: {})", admin, employeeId, warnings.size());
        events.publishEvent(new OverlayChanged(null));
        return new HideResult(employeeId, true, warnings);
    }

    /** The other heads of the function move up so that the ranks stay 1..n */
    private void dropHead(EmployeeOverride head, String admin) {
        String cs = head.getCs();
        head.setHeadRank(null);
        head.setUpdatedBy(admin);
        overlay.saveAndFlush(head);
        List<EmployeeOverride> others = overlay.findByCs(cs).stream().filter(o -> o.getHeadRank() != null)
                .sorted(Comparator.comparing(EmployeeOverride::getHeadRank)).toList();
        others.forEach(o -> o.setHeadRank(null));
        overlay.saveAllAndFlush(others);
        short rank = 1;
        for (EmployeeOverride o : others) {
            o.setHeadRank(rank++);
        }
        overlay.saveAllAndFlush(others);
    }

    @Transactional
    public void restore(long employeeId, String admin) {
        guards.requireEditing();
        EmployeeVisibility row = visibility.findById(employeeId).filter(EmployeeVisibility::isHidden)
                .orElseThrow(() -> AdminEditException.notFound("NOT_HIDDEN", "This person is not hidden."));
        visibility.delete(row);
        visibility.flush();
        log.info("Admin {} restored employee {}", admin, employeeId);
        events.publishEvent(new OverlayChanged(null));
    }

    @Transactional(readOnly = true)
    public List<HiddenView> hidden() {
        guards.requireEditing();
        return visibility.findByHiddenTrueOrderByHiddenAtDesc().stream().map(v -> {
            Employee e = employees.findById(v.getEmployeeId()).orElse(null);
            return new HiddenView(v.getEmployeeId(), e == null ? null : e.getDisplayName(),
                    e == null ? null : (e.getEmail() != null ? e.getEmail() : e.getUpn()),
                    e == null ? null : e.getJobTitle(), v.getHiddenBy(), v.getHiddenAt(), v.getReason());
        }).toList();
    }
}
