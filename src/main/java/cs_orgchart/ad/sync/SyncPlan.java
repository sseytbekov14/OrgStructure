package cs_orgchart.ad.sync;

import cs_orgchart.ad.mapper.AdEmployeeMapper.SkipReason;
import cs_orgchart.ad.mapper.NormalizedDepartment;
import cs_orgchart.ad.model.AdEmployee;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What a sync would write, worked out in memory without touching the database (spec v3 §7 steps 3–4 and the
 * resolution of 6.2–6.4): the real sync applies it, the dry run reports it.
 *
 * @param ldapResultSize     entries returned by the directory: fetched + skipped
 * @param skipped            records dropped by the validation, per reason
 * @param members            the valid Central Services employees with their resolved manager and PM
 * @param externals          managers/PMs outside the fetched set, found in AD (exempt from the country rule)
 * @param invalidExternals   managers/PMs found in AD but not usable (link left empty)
 * @param unmappedSegments   department segments the dictionaries do not know
 * @param unmappedFunctions  level-2 departments without a site function
 */
public record SyncPlan(int ldapResultSize, Map<SkipReason, Integer> skipped, List<Member> members,
                       List<AdEmployee> externals, int invalidExternals, List<String> unmappedSegments,
                       List<String> unmappedFunctions) {

    /** How the line manager of a member was found */
    public enum ManagerMatch { NONE, FETCHED, EXTERNAL, UNRESOLVED }

    /** How the performance manager of a member was found (§7 step 6.3) */
    public enum PmMatch { NONE, UPN, EMAIL, LOCAL_PART, EXTERNAL, UNRESOLVED }

    /**
     * @param managerGuid null: no manager (no manager DN, or not found)
     * @param pmGuid      null: no PM (no PM UPN, or not found)
     */
    public record Member(AdEmployee employee, NormalizedDepartment department, String csName,
                         UUID managerGuid, ManagerMatch managerMatch, UUID pmGuid, PmMatch pmMatch) {
    }

    public int fetchedCount() {
        return members.size();
    }

    public int skippedCount() {
        return skipped.values().stream().mapToInt(Integer::intValue).sum();
    }
}
