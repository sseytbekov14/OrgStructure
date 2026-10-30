package cs_orgchart.employee.group;

import java.util.List;

/**
 * Requests and answers of the group endpoints (U4, /api/manage/**).
 */
public final class GroupViews {

    private GroupViews() {
    }

    /** A person in the manage view: name, job title, country code and how they are in the group */
    public record MemberRef(Long employeeId, String name, String jobTitle, String countryCode, String source) {
        public static final String MANUAL = "MANUAL";
        public static final String AD = "AD";
    }

    public record GroupView(Long id, String cs, String name, String kind, String source, int sortOrder,
                            boolean active, Long version, int memberCount, List<MemberRef> members) {
    }

    /**
     * GET /api/manage/functions/{cs}/groups: every group (also empty ones) in order, the heads and the people
     * without a group
     */
    public record GroupsView(String cs, List<GroupView> groups, List<MemberRef> heads, List<MemberRef> ungrouped) {
    }

    /** POST /api/manage/functions/{cs}/groups; kind TEAM (default) or COUNTRY */
    public record CreateGroupRequest(String name, String kind) {
    }

    /**
     * PUT /api/manage/groups/{id}: a new name, a new position among the groups of the function (0 = first) and/or
     * active; version of the group as read
     */
    public record UpdateGroupRequest(String name, Integer position, Boolean active, Long version) {
    }

    /** POST /api/manage/groups/{id}/members: at most 50 people, added or moved here */
    public record MembersRequest(List<Long> employeeIds) {
    }

    /**
     * PUT /api/manage/groups/{id}/order, and /api/manage/functions/{cs}/order for the people without a group: the
     * people in their new order (the first is on top). Everybody listed must be there; who is not listed follows, by
     * grade.
     */
    public record MemberOrderRequest(List<Long> employeeIds) {
    }

    /** Where a moved person was before (previousGroupId null: the AD default group), for Undo */
    public record Moved(Long employeeId, Long previousGroupId, String previousGroupName) {
    }

    public record MembersResult(Long groupId, List<Moved> moved) {
    }
}
