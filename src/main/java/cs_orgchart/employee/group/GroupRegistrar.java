package cs_orgchart.employee.group;

import cs_orgchart.ad.sync.EmployeesSynced;
import cs_orgchart.employee.DbEmployeeDirectory;
import cs_orgchart.employee.EmployeeDtoAssembler.AdPlacement;
import cs_orgchart.employee.EmployeeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Registers the AD default groups (last non-general department level, spec §5.5) as AUTO groups of their function
 * (U4): after each AD sync and when the application starts in db mode. Idempotent; never deletes a group. Runs first
 * (before the mock overlay seeder, which then finds the AD groups, and before the directory is rebuilt).
 */
@Slf4j
@Component
public class GroupRegistrar {

    private final EmployeeRepository employees;
    private final ObjectProvider<DbEmployeeDirectory> dbDirectory;
    private final FunctionGroupService groups;

    public GroupRegistrar(EmployeeRepository employees, ObjectProvider<DbEmployeeDirectory> dbDirectory,
                          FunctionGroupService groups) {
        this.employees = employees;
        this.dbDirectory = dbDirectory;
        this.groups = groups;
    }

    @EventListener
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onEmployeesSynced(EmployeesSynced synced) {
        register("ad-sync");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        register("startup");
    }

    /** @return the number of groups registered now (0 outside db mode) */
    public int register(String trigger) {
        DbEmployeeDirectory db = dbDirectory.getIfAvailable();
        if (db == null) {
            return 0;
        }
        try {
            List<AdPlacement> placements = employees.findAll().stream()
                    .filter(e -> e.isEnabled() && !e.isExternal())
                    .map(db::adPlacement)
                    .toList();
            int created = groups.registerAutoGroups(placements);
            if (created > 0) {
                log.info("AD default groups: {} registered ({})", created, trigger);
                db.rebuild();
            }
            return created;
        } catch (RuntimeException e) {
            // The site keeps working with the groups it has; the next sync or start tries again
            log.warn("AD default groups could not be registered ({}): {}", trigger, e.getClass().getSimpleName());
            return 0;
        }
    }
}
