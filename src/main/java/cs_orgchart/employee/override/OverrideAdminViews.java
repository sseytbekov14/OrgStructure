package cs_orgchart.employee.override;

import java.time.Instant;
import java.util.List;

/**
 * Requests and answers of the admin overlay endpoints (spec v3 §10.2).
 */
public final class OverrideAdminViews {

    private OverrideAdminViews() {
    }

    /**
     * PUT /api/manage/overrides/{employeeId}?cs=: the three texts as they should be (null or empty: none) and the
     * version of the row that was read (null: there is no row yet).
     */
    public record OverrideRequest(String groupName, String areaOfDuties, String jobDescription, Long version) {
    }

    /** The overlay row of a person in a function */
    public record OverrideView(Long employeeId, String cs, String groupName, Short headRank, String areaOfDuties,
                               String jobDescription, Long managerOverrideId, Long version, String updatedBy,
                               Instant updatedAt) {

        static OverrideView of(EmployeeOverride o) {
            return new OverrideView(o.getEmployeeId(), o.getCs(), o.getGroupName(), o.getHeadRank(),
                    o.getAreaOfDuties(), o.getJobDescription(), o.getManagerOverrideId(), o.getVersion(),
                    o.getUpdatedBy(), o.getUpdatedAt());
        }
    }

    /**
     * A person of the site with their overlay row in that function (null: none)
     *
     * @param group the group shown on the site (HEAD for a head, else the overlay or the default group)
     */
    public record MemberView(Long employeeId, String name, String email, String jobTitle, String countryCode,
                             String cs, String group, OverrideView override) {
    }

    /**
     * PUT /api/manage/functions/{cs}/heads
     *
     * @param employeeIds           the heads in order (rank 1 first)
     * @param expectedHeads         the heads as the page showed them (refused with 409 when they changed since)
     * @param confirmRemoveAllHeads must be true to leave the function without a head
     */
    public record HeadsRequest(List<Long> employeeIds, List<Long> expectedHeads, boolean confirmRemoveAllHeads) {
    }

    public record HeadView(Long employeeId, String name, String jobTitle, int rank) {
    }

    /**
     * @param hiddenHeads heads whose account is disabled in AD: not shown, kept after the others
     */
    public record HeadsView(String cs, List<HeadView> heads, int hiddenHeads) {
    }
}
