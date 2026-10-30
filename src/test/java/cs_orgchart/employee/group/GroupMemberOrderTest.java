package cs_orgchart.employee.group;

import cs_orgchart.access.DbModeMvcSupport;
import cs_orgchart.ad.sync.SyncStatus;
import cs_orgchart.ad.sync.SyncTrigger;
import cs_orgchart.employee.DbEmployeeDirectory;
import cs_orgchart.employee.EmployeeDirectory;
import cs_orgchart.model.Employee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The order of the people inside a group (PUT / DELETE /api/manage/groups/{id}/order): admins only, members only, the
 * directory follows it (the order goes before the grade inside the group, other groups are untouched), it survives an
 * AD sync, it does not pin anybody to the function, and it goes with the group or the person.
 */
class GroupMemberOrderTest extends DbModeMvcSupport {

    @Autowired
    private EmployeeDirectory directory;

    @Autowired
    private DbEmployeeDirectory dbDirectory;

    private long groupId;
    private List<Long> byGrade;
    private String cs;

    /** The largest group of the mock directory, in the order the site shows it now */
    @BeforeEach
    void pickAGroup() {
        Map.Entry<Long, List<Employee>> largest = directory.getAllEmployees().stream()
                .filter(e -> e.getGroupId() != null)
                .collect(java.util.stream.Collectors.groupingBy(Employee::getGroupId)).entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().size())).orElseThrow();
        groupId = largest.getKey();
        cs = largest.getValue().get(0).getCs();
        byGrade = largest.getValue().stream().map(Employee::getId).toList();
        assertThat(byGrade.size()).as("a group with several people").isGreaterThan(1);
    }

    private List<Long> shown(long group) {
        return directory.getAllEmployees().stream().filter(e -> Long.valueOf(group).equals(e.getGroupId()))
                .map(Employee::getId).toList();
    }

    private List<Long> reversed() {
        List<Long> order = new ArrayList<>(byGrade);
        java.util.Collections.reverse(order);
        return order;
    }

    private MockHttpServletRequestBuilder order() {
        return put("/api/manage/groups/" + groupId + "/order");
    }

    private void save(List<Long> ids) throws Exception {
        mvc.perform(write(order(), editor, Map.of("employeeIds", ids))).andExpect(status().isNoContent());
    }

    @Test
    void anAdminPutsAPersonOnTopWhateverTheGrade() throws Exception {
        save(reversed());

        assertThat(shown(groupId)).containsExactlyElementsOf(reversed());
        Employee first = directory.getAllEmployees().stream().filter(e -> e.getId().equals(reversed().get(0)))
                .filter(e -> e.getCs().equals(cs)).findFirst().orElseThrow();
        assertThat(first.getGroupPosition()).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM group_member_order WHERE group_id = ?", Integer.class, groupId))
                .isEqualTo(byGrade.size());
    }

    @Test
    void theReadersSeeTheOrderInTheApiToo() throws Exception {
        save(reversed());

        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/employees").session(reader)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(body).contains("\"groupPosition\":10", "\"groupId\":" + groupId);
    }

    @Test
    void whoIsNotListedFollowsByGrade() throws Exception {
        long last = byGrade.get(byGrade.size() - 1);
        save(List.of(last));

        List<Long> expected = new ArrayList<>(List.of(last));
        byGrade.stream().filter(id -> id != last).forEach(expected::add);
        assertThat(shown(groupId)).containsExactlyElementsOf(expected);
    }

    @Test
    void savingAgainReplacesTheOrder() throws Exception {
        save(reversed());
        save(byGrade);

        assertThat(shown(groupId)).containsExactlyElementsOf(byGrade);
    }

    @Test
    void resetGoesBackToTheGrades() throws Exception {
        save(reversed());

        mvc.perform(write(delete("/api/manage/groups/" + groupId + "/order"), editor, null))
                .andExpect(status().isNoContent());

        assertThat(shown(groupId)).containsExactlyElementsOf(byGrade);
        assertThat(directory.getAllEmployees()).allMatch(e -> e.getGroupPosition() == null);
    }

    @Test
    void onlyPeopleOfTheGroupCanBeListedOnceEach() throws Exception {
        long stranger = directory.getAllEmployees().stream().filter(e -> !byGrade.contains(e.getId())).findFirst()
                .orElseThrow().getId();
        mvc.perform(write(order(), editor, Map.of("employeeIds", List.of(byGrade.get(0), stranger))))
                .andExpect(status().isConflict());
        mvc.perform(write(order(), editor, Map.of("employeeIds", List.of(byGrade.get(0), byGrade.get(0)))))
                .andExpect(status().isBadRequest());
        mvc.perform(write(order(), editor, Map.of("employeeIds", List.of()))).andExpect(status().isBadRequest());
        mvc.perform(write(put("/api/manage/groups/987654321/order"), editor, Map.of("employeeIds", byGrade)))
                .andExpect(status().isNotFound());
        assertThat(shown(groupId)).containsExactlyElementsOf(byGrade);
    }

    @Test
    void onlyAdminsMayChangeTheOrder() throws Exception {
        Map<String, List<Long>> body = Map.of("employeeIds", reversed());
        mvc.perform(write(order(), reader, body)).andExpect(status().isForbidden());
        mvc.perform(write(order(), null, body)).andExpect(status().isUnauthorized());
        mvc.perform(write(delete("/api/manage/groups/" + groupId + "/order"), reader, null))
                .andExpect(status().isForbidden());
        assertThat(shown(groupId)).containsExactlyElementsOf(byGrade);
    }

    @Test
    void anAdSyncKeepsTheOrder() throws Exception {
        save(reversed());

        assertThat(sync.sync(SyncTrigger.MANUAL).status()).isEqualTo(SyncStatus.SUCCESS);
        dbDirectory.load();

        assertThat(shown(groupId)).containsExactlyElementsOf(reversed());
    }

    @Test
    void thePeopleWithoutAGroupCanBeOrderedToo() throws Exception {
        Map.Entry<String, List<Employee>> other = directory.getAllEmployees().stream()
                .filter(e -> e.getGroup() == null && e.getId() != null)
                .collect(java.util.stream.Collectors.groupingBy(Employee::getCs)).entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().size())).orElseThrow();
        String function = other.getKey();
        List<Long> people = other.getValue().stream().map(Employee::getId).toList();
        assertThat(people.size()).as("an Other column with several people").isGreaterThan(1);
        List<Long> order = new ArrayList<>(people);
        java.util.Collections.reverse(order);

        mvc.perform(write(put("/api/manage/functions/{cs}/order", function), editor, Map.of("employeeIds", order)))
                .andExpect(status().isNoContent());

        assertThat(directory.getAllEmployees().stream().filter(e -> function.equals(e.getCs()) && e.getGroup() == null)
                .map(Employee::getId).toList()).containsExactlyElementsOf(order);

        // Somebody of a real group does not belong here
        mvc.perform(write(put("/api/manage/functions/{cs}/order", cs), editor, Map.of("employeeIds", byGrade)))
                .andExpect(status().isConflict());
        mvc.perform(write(put("/api/manage/functions/{cs}/order", function), reader, Map.of("employeeIds", order)))
                .andExpect(status().isForbidden());

        mvc.perform(write(delete("/api/manage/functions/{cs}/order", function), editor, null))
                .andExpect(status().isNoContent());
        assertThat(directory.getAllEmployees().stream().filter(e -> function.equals(e.getCs()) && e.getGroup() == null)
                .map(Employee::getId).toList()).containsExactlyElementsOf(people);
    }

    @Test
    void aMoveToAnotherGroupLeavesTheOldPositionBehind() throws Exception {
        save(reversed());
        long mover = reversed().get(0);
        long otherGroup = jdbc.queryForObject("SELECT id FROM function_groups WHERE cs = ? AND id <> ? ORDER BY id LIMIT 1",
                Long.class, cs, groupId);

        mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/manage/groups/" + otherGroup + "/members"), editor, Map.of("employeeIds", List.of(mover))))
                .andExpect(status().isOk());

        Employee moved = directory.getAllEmployees().stream().filter(e -> e.getId() == mover && cs.equals(e.getCs()))
                .findFirst().orElseThrow();
        assertThat(moved.getGroupId()).isEqualTo(otherGroup);
        assertThat(moved.getGroupPosition()).as("the position was for the old group").isNull();
    }

    @Test
    void theOrderDoesNotPinAnybodyToTheFunction() throws Exception {
        int overlayBefore = jdbc.queryForObject("SELECT count(*) FROM employee_overrides", Integer.class);

        save(reversed());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_overrides", Integer.class))
                .as("no overlay row: AD can still move the person to another function").isEqualTo(overlayBefore);
    }

    @Test
    void theOrderGoesWithTheGroupOrThePerson() throws Exception {
        save(reversed());
        jdbc.update("DELETE FROM employees WHERE id = ?", byGrade.get(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM group_member_order WHERE employee_id = ?", Integer.class,
                byGrade.get(0))).isZero();
        jdbc.update("UPDATE employee_overrides SET group_id = NULL WHERE group_id = ?", groupId);
        jdbc.update("DELETE FROM function_groups WHERE id = ?", groupId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM group_member_order WHERE group_id = ?", Integer.class,
                groupId)).isZero();
    }
}
