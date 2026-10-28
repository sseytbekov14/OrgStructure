package cs_orgchart.ad.sync;

import cs_orgchart.ad.mapper.AdEmployeeMapper.SkipReason;
import cs_orgchart.ad.sync.SyncPlan.ManagerMatch;
import cs_orgchart.ad.sync.SyncPlan.Member;
import cs_orgchart.ad.sync.SyncPlan.PmMatch;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * The report of a dry run (spec v3 §7): what a sync would find and link, without writing anything. Counts only;
 * department names are organizational units, no names, logins or e-mails.
 *
 * @param status       OK, FAILED (AD could not be read) or SUSPENDED (after invalid credentials)
 * @param safetyCheck  PASS, or why a real sync would fail with SAFETY
 * @param photoCandidates employees whose thumbnailPhoto a run would read (no photo is read); null: AD photos off
 */
public record DryRunReport(
        Instant generatedAt, String trigger, String status, String errorCode, String errorMessage, long durationMs,
        int ldapResultSize, int fetchedCount, int skippedCount, Map<SkipReason, Integer> skippedByReason,
        Map<String, Integer> byCountry, Map<String, Integer> byFunction,
        List<String> unmappedSegments, List<String> unmappedFunctions,
        int withoutManager, int withoutPm, int withoutManagerAndPm,
        Map<ManagerMatch, Integer> managers, Map<PmMatch, Integer> pms,
        int pmEqualsManager, int pmDiffersFromManager, int pmResolvedByLocalPart,
        int externals, int invalidExternals,
        long currentEmployees, double minRatio, String safetyCheck, Integer photoCandidates) {

    public static DryRunReport of(SyncPlan plan, SyncTrigger trigger, Instant at, long durationMs,
                                  long currentEmployees, double minRatio, String safetyCheck) {
        Map<String, Integer> byCountry = new TreeMap<>();
        Map<String, Integer> byFunction = new TreeMap<>();
        Map<ManagerMatch, Integer> managers = new EnumMap<>(ManagerMatch.class);
        Map<PmMatch, Integer> pms = new EnumMap<>(PmMatch.class);
        for (ManagerMatch m : ManagerMatch.values()) {
            managers.put(m, 0);
        }
        for (PmMatch p : PmMatch.values()) {
            pms.put(p, 0);
        }
        int withoutManager = 0;
        int withoutPm = 0;
        int withoutBoth = 0;
        int pmEqualsManager = 0;
        int pmDiffers = 0;
        for (Member member : plan.members()) {
            byCountry.merge(member.employee().countryCode(), 1, Integer::sum);
            byFunction.merge(Objects.requireNonNullElse(member.csName(), "(no function)"), 1, Integer::sum);
            managers.merge(member.managerMatch(), 1, Integer::sum);
            pms.merge(member.pmMatch(), 1, Integer::sum);
            boolean noManager = member.employee().managerDn() == null;
            boolean noPm = member.employee().pmUpn() == null;
            withoutManager += noManager ? 1 : 0;
            withoutPm += noPm ? 1 : 0;
            withoutBoth += noManager && noPm ? 1 : 0;
            if (member.managerGuid() != null && member.pmGuid() != null) {
                if (member.managerGuid().equals(member.pmGuid())) {
                    pmEqualsManager++;
                } else {
                    pmDiffers++;
                }
            }
        }
        return new DryRunReport(at, trigger.name(), "OK", null, null, durationMs,
                plan.ldapResultSize(), plan.fetchedCount(), plan.skippedCount(), new EnumMap<>(plan.skipped()),
                byCountry, byFunction, plan.unmappedSegments(), plan.unmappedFunctions(),
                withoutManager, withoutPm, withoutBoth, managers, pms, pmEqualsManager, pmDiffers,
                pms.get(PmMatch.LOCAL_PART), plan.externals().size(), plan.invalidExternals(),
                currentEmployees, minRatio, safetyCheck, null);
    }

    /** AD could not be read (FAILED) or was not contacted (SUSPENDED) */
    public static DryRunReport notRun(String status, SyncTrigger trigger, Instant at, long durationMs,
                                      String errorCode, String errorMessage, long currentEmployees, double minRatio) {
        return new DryRunReport(at, trigger.name(), status, errorCode, errorMessage, durationMs, 0, 0, 0, Map.of(),
                Map.of(), Map.of(), List.of(), List.of(), 0, 0, 0, Map.of(), Map.of(), 0, 0, 0, 0, 0,
                currentEmployees, minRatio, "NOT CHECKED", null);
    }

    /** The same report with the number of employees whose photo a run would read now (null: photos off) */
    public DryRunReport withPhotoCandidates(Integer candidates) {
        return new DryRunReport(generatedAt, trigger, status, errorCode, errorMessage, durationMs, ldapResultSize,
                fetchedCount, skippedCount, skippedByReason, byCountry, byFunction, unmappedSegments, unmappedFunctions,
                withoutManager, withoutPm, withoutManagerAndPm, managers, pms, pmEqualsManager, pmDiffersFromManager,
                pmResolvedByLocalPart, externals, invalidExternals, currentEmployees, minRatio, safetyCheck, candidates);
    }

    /** One line for the log */
    public String summary() {
        if (!"OK".equals(status)) {
            return "AD dry run " + status + (errorCode == null ? "" : " (" + errorCode + "): " + errorMessage);
        }
        Map<String, Object> parts = new LinkedHashMap<>();
        parts.put("fetched", fetchedCount);
        parts.put("skipped", skippedCount + " " + skippedByReason);
        parts.put("countries", byCountry);
        parts.put("functions", byFunction);
        parts.put("unmapped segments", unmappedSegments.size());
        parts.put("without manager", withoutManager);
        parts.put("without PM", withoutPm);
        parts.put("unresolved managers", managers.get(ManagerMatch.UNRESOLVED));
        parts.put("unresolved PMs", pms.get(PmMatch.UNRESOLVED));
        parts.put("PM by local part", pmResolvedByLocalPart);
        parts.put("externals", externals);
        parts.put("safety", safetyCheck);
        return "AD dry run OK: " + parts;
    }
}
