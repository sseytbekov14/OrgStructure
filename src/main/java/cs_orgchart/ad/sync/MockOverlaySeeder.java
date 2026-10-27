package cs_orgchart.ad.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.employee.Employee;
import cs_orgchart.employee.EmployeeRepository;
import cs_orgchart.employee.override.EmployeeOverride;
import cs_orgchart.employee.override.EmployeeOverrideRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

/**
 * AD_SYNC_MODE=mock only: fills employee_overrides from {@value #RESOURCE} after a sync, when the table is still
 * empty (so it never overwrites an edit). Not part of the sync: the sync itself never touches the overlay. Runs
 * before the other listeners of {@link EmployeesSynced}, so that they see the overlay.
 */
@Slf4j
public class MockOverlaySeeder {

    public static final String RESOURCE = "mock/overrides.json";
    static final String SEEDED_BY = "mock-seed";

    record Row(String upn, String cs, String groupName, Short headRank, String areaOfDuties, String jobDescription,
               String managerOverrideUpn) {
    }

    private final EmployeeRepository employees;
    private final EmployeeOverrideRepository overrides;
    private final ObjectMapper json;

    public MockOverlaySeeder(EmployeeRepository employees, EmployeeOverrideRepository overrides, ObjectMapper json) {
        this.employees = employees;
        this.overrides = overrides;
        this.json = json;
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
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
            overrides.save(EmployeeOverride.builder().employeeId(employee.get().getId()).cs(row.cs())
                    .groupName(row.groupName()).headRank(row.headRank()).areaOfDuties(row.areaOfDuties())
                    .jobDescription(row.jobDescription()).managerOverrideId(managerOverride).updatedBy(SEEDED_BY)
                    .build());
            seeded++;
        }
        log.info("Mock overlay: {} rows from {}", seeded, RESOURCE);
    }

    private List<Row> read() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return json.readValue(in, new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException(RESOURCE + " cannot be read", e);
        }
    }
}
