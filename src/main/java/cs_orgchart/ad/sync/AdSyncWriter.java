package cs_orgchart.ad.sync;

import cs_orgchart.ad.mapper.NormalizedDepartment;
import cs_orgchart.ad.model.AdEmployee;
import cs_orgchart.ad.sync.SyncPlan.Member;
import cs_orgchart.employee.Employee;
import cs_orgchart.employee.EmployeeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Applies a {@link SyncPlan} in the caller's transaction (spec v3 §7 step 6): upsert by ad_guid (new rows inserted,
 * changed fields updated, enabled = true, is_external = false), the external managers/PMs (is_external = true, no
 * country), manager_id and pm_id, and enabled = false for the employees no longer in AD (never deleted).
 * The overlay (employee_overrides) is never read or written here.
 */
@Slf4j
public class AdSyncWriter {

    private static final int DN_MAX = 512;
    private static final int UPN_MAX = 256;

    private final EmployeeRepository employees;

    public AdSyncWriter(EmployeeRepository employees) {
        this.employees = employees;
    }

    public SyncCounts apply(SyncPlan plan, Instant now) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("AdSyncWriter.apply needs the transaction of the sync");
        }
        List<Employee> rows = employees.findAll();
        Map<UUID, Employee> byGuid = new HashMap<>();
        rows.forEach(row -> byGuid.put(row.getAdGuid(), row));
        Set<UUID> memberGuids = new HashSet<>();
        plan.members().forEach(m -> memberGuids.add(m.employee().adGuid()));
        Set<UUID> targetGuids = new HashSet<>(memberGuids);
        plan.externals().forEach(x -> targetGuids.add(x.adGuid()));

        if (releaseKeysOfStaleRows(rows, targetGuids, plan)) {
            // The released keys must be in the database before an insert (run at once: identity ids) takes them
            employees.flush();
        }

        Set<UUID> inserted = new HashSet<>();
        Set<UUID> changed = new HashSet<>();
        for (Member member : plan.members()) {
            upsert(member.employee(), member.department(), false, now, byGuid, inserted, changed);
        }
        for (AdEmployee external : plan.externals()) {
            upsert(external, NormalizedDepartment.EMPTY, true, now, byGuid, inserted, changed);
        }

        // Links after the inserts: new managers have their id now. Externals keep theirs (one level only).
        for (Member member : plan.members()) {
            Employee row = byGuid.get(member.employee().adGuid());
            boolean linkChanged = set(row::getManagerId, row::setManagerId, idOf(member.managerGuid(), byGuid))
                    | set(row::getPmId, row::setPmId, idOf(member.pmGuid(), byGuid));
            if (linkChanged && !inserted.contains(row.getAdGuid())) {
                changed.add(row.getAdGuid());
            }
        }
        changed.forEach(guid -> byGuid.get(guid).setUpdatedAt(now));

        int disabled = 0;
        for (Employee row : rows) {
            if (!row.isExternal() && row.isEnabled() && !targetGuids.contains(row.getAdGuid())) {
                row.setEnabled(false);
                row.setUpdatedAt(now);
                disabled++;
            }
        }
        // Constraint violations surface here, inside the sync's try, not at commit
        employees.flush();

        return new SyncCounts(plan.fetchedCount(), plan.skippedCount(), inserted.size(), changed.size(), disabled,
                plan.externals().size(), plan.unmappedSegments().size(),
                (int) plan.members().stream().filter(m -> m.pmMatch() == SyncPlan.PmMatch.LOCAL_PART).count());
    }

    private void upsert(AdEmployee source, NormalizedDepartment department, boolean external, Instant now,
                        Map<UUID, Employee> byGuid, Set<UUID> inserted, Set<UUID> changed) {
        Employee row = byGuid.get(source.adGuid());
        boolean isNew = row == null;
        if (isNew) {
            row = Employee.builder().createdAt(now).updatedAt(now).build();
        }
        boolean differs = copy(source, department, external, row);
        row.setLastSyncedAt(now);
        if (isNew) {
            row = employees.save(row);
            byGuid.put(source.adGuid(), row);
            inserted.add(source.adGuid());
        } else if (differs) {
            changed.add(source.adGuid());
        }
    }

    /** The AD values into the row; whether anything differed */
    private static boolean copy(AdEmployee e, NormalizedDepartment d, boolean external, Employee row) {
        boolean changed = false;
        changed |= set(row::getAdGuid, row::setAdGuid, e.adGuid());
        changed |= set(row::getDn, row::setDn, e.dn());
        changed |= set(row::getLogin, row::setLogin, e.login());
        changed |= set(row::getUpn, row::setUpn, e.upn());
        changed |= set(row::getEmail, row::setEmail, e.email());
        changed |= set(row::getEmployeeId, row::setEmployeeId, e.employeeId());
        changed |= set(row::getDisplayName, row::setDisplayName, e.displayName());
        changed |= set(row::getFirstName, row::setFirstName, e.firstName());
        changed |= set(row::getLastName, row::setLastName, e.lastName());
        changed |= set(row::getJobTitle, row::setJobTitle, e.jobTitle());
        changed |= set(row::getCompany, row::setCompany, e.company());
        changed |= set(row::getDepartment, row::setDepartment, e.department());
        changed |= set(row::getDeptLevel1, row::setDeptLevel1, d.level1());
        changed |= set(row::getDeptLevel2, row::setDeptLevel2, d.level2());
        changed |= set(row::getDeptLevel3, row::setDeptLevel3, d.level3());
        changed |= set(row::getDeptLevel4, row::setDeptLevel4, d.level4());
        changed |= set(row::getManagerDn, row::setManagerDn, e.managerDn());
        changed |= set(row::getPmUpn, row::setPmUpn, e.pmUpn());
        changed |= set(row::getCountryCode, row::setCountryCode, external ? null : e.countryCode());
        changed |= set(row::getOffice, row::setOffice, e.office());
        changed |= set(row::getCity, row::setCity, e.city());
        changed |= set(row::getPhone, row::setPhone, e.phone());
        changed |= set(row::getMobile, row::setMobile, e.mobile());
        changed |= set(row::getAdWhenChanged, row::setAdWhenChanged, e.adWhenChanged());
        // Synced employees are enabled (the filter fetches enabled accounts only); externals as AD says
        changed |= set(row::isEnabled, row::setEnabled, external ? e.enabled() : true);
        changed |= set(row::isExternal, row::setExternal, external);
        return changed;
    }

    /**
     * An account deleted and created again in AD has a new objectGUID but its old DN or UPN: the old row (not in
     * this sync) gets a marked key, so that the unique indexes do not fail every sync from now on.
     */
    /** @return whether a key was released */
    private static boolean releaseKeysOfStaleRows(List<Employee> rows, Set<UUID> targetGuids, SyncPlan plan) {
        Map<String, Employee> staleByDn = new HashMap<>();
        Map<String, Employee> staleByUpn = new HashMap<>();
        for (Employee row : rows) {
            if (!targetGuids.contains(row.getAdGuid())) {
                staleByDn.put(lower(row.getDn()), row);
                staleByUpn.put(lower(row.getUpn()), row);
            }
        }
        if (staleByDn.isEmpty()) {
            return false;
        }
        boolean anyReleased = false;
        List<AdEmployee> targets = new ArrayList<>(plan.externals());
        plan.members().forEach(m -> targets.add(m.employee()));
        for (AdEmployee target : targets) {
            Employee dnHolder = staleByDn.remove(lower(target.dn()));
            if (dnHolder != null) {
                dnHolder.setDn(released(dnHolder.getDn(), dnHolder.getId(), DN_MAX));
                anyReleased = true;
                log.warn("AD sync: employee id={} (no longer in AD) had the DN of objectGUID {}; its DN is marked as"
                        + " released", dnHolder.getId(), target.adGuid());
            }
            Employee upnHolder = staleByUpn.remove(lower(target.upn()));
            if (upnHolder != null) {
                upnHolder.setUpn(released(upnHolder.getUpn(), upnHolder.getId(), UPN_MAX));
                anyReleased = true;
                log.warn("AD sync: employee id={} (no longer in AD) had the UPN of objectGUID {}; its UPN is marked as"
                        + " released", upnHolder.getId(), target.adGuid());
            }
        }
        return anyReleased;
    }

    private static String released(String key, Long id, int max) {
        String suffix = "#released-" + id;
        return (key.length() + suffix.length() > max ? key.substring(0, max - suffix.length()) : key) + suffix;
    }

    private static Long idOf(UUID guid, Map<UUID, Employee> byGuid) {
        if (guid == null) {
            return null;
        }
        Employee row = byGuid.get(guid);
        return row == null ? null : row.getId();
    }

    private static <T> boolean set(Supplier<T> get, Consumer<T> set, T value) {
        if (Objects.equals(get.get(), value)) {
            return false;
        }
        set.accept(value);
        return true;
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
