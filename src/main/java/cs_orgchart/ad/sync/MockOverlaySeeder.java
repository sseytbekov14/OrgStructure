package cs_orgchart.ad.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.employee.Employee;
import cs_orgchart.employee.EmployeeRepository;
import cs_orgchart.employee.group.FunctionGroup;
import cs_orgchart.employee.group.FunctionGroupService;
import cs_orgchart.employee.override.EmployeeOverride;
import cs_orgchart.employee.override.EmployeeOverrideRepository;
import cs_orgchart.employee.visibility.EmployeeVisibility;
import cs_orgchart.employee.visibility.EmployeeVisibilityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * AD_SYNC_MODE=mock only: fills employee_overrides from {@value #RESOURCE} after a sync, when the table is still
 * empty (so it never overwrites an edit). Not part of the sync: the sync itself never touches the overlay. Runs
 * before the other listeners of {@link EmployeesSynced} (after the AD default groups are registered, U4), so that they see the overlay.
 */
@Slf4j
public class MockOverlaySeeder {

    public static final String RESOURCE = "mock/overrides.json";
    static final String SEEDED_BY = "mock-seed";

    record Row(String upn, String cs, String groupName, Short headRank, String areaOfDuties, String jobDescription,
               String managerOverrideUpn) {
    }

    /** U4: people of the mock hidden from the site (a manager, to show the re-parenting of their counselees) */
    public static final String HIDDEN_RESOURCE = "mock/hidden.json";

    record HiddenRow(String upn, String reason) {
    }

    private final EmployeeRepository employees;
    private final EmployeeOverrideRepository overrides;
    private final ObjectMapper json;
    private final FunctionGroupService groups;
    private final EmployeeVisibilityRepository visibility;

    public MockOverlaySeeder(EmployeeRepository employees, EmployeeOverrideRepository overrides, ObjectMapper json,
                             FunctionGroupService groups, EmployeeVisibilityRepository visibility) {
        this.employees = employees;
        this.overrides = overrides;
        this.json = json;
        this.groups = groups;
        this.visibility = visibility;
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    @Transactional
    public void onEmployeesSynced(EmployeesSynced synced) {
        if (overrides.count() > 0) {
            return;
        }
        int seeded = 0;
        for (Row row : read()) {
            Optional<Employee> employee = employees.findFirstByUpnIgnoreCase(row.upn());
            if (employee.isEmpty()) {
                log.warn("Mock overlay: no employee with the UPN of a row of {}, row skipped", RESOURCE);
                continue;
            }
            Long managerOverride = row.managerOverrideUpn() == null ? null
                    : employees.findFirstByUpnIgnoreCase(row.managerOverrideUpn()).map(Employee::getId).orElse(null);
            FunctionGroup group = row.groupName() == null ? null
                    : groups.resolveOrCreate(row.cs(), row.groupName(), FunctionGroup.MANUAL, SEEDED_BY);
            overrides.save(EmployeeOverride.builder().employeeId(employee.get().getId()).cs(row.cs())
                    .groupName(group == null ? null : group.getName()).groupId(group == null ? null : group.getId())
                    .headRank(row.headRank()).areaOfDuties(row.areaOfDuties())
                    .jobDescription(row.jobDescription()).managerOverrideId(managerOverride).updatedBy(SEEDED_BY)
                    .build());
            seeded++;
        }
        int hidden = 0;
        if (visibility.count() == 0) {
            for (HiddenRow row : read(HIDDEN_RESOURCE, new TypeReference<List<HiddenRow>>() {
            })) {
                Optional<Employee> employee = employees.findFirstByUpnIgnoreCase(row.upn());
                if (employee.isPresent()) {
                    visibility.save(EmployeeVisibility.builder().employeeId(employee.get().getId()).hidden(true)
                            .hiddenBy(SEEDED_BY).hiddenAt(Instant.now()).reason(row.reason()).build());
                    hidden++;
                }
            }
        }
        log.info("Mock overlay: {} rows from {}, {} hidden people from {}", seeded, RESOURCE, hidden, HIDDEN_RESOURCE);
    }

    private List<Row> read() {
        return read(RESOURCE, new TypeReference<>() {
        });
    }

    private <T> T read(String resource, TypeReference<T> type) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return json.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException(resource + " cannot be read", e);
        }
    }
}
