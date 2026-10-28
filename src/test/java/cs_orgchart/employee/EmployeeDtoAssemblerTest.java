package cs_orgchart.employee;

import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.ad.mapper.FunctionResolver;
import cs_orgchart.department.SpecDepartmentDictionaries;
import cs_orgchart.employee.EmployeeSourceProperties.ParentSource;
import cs_orgchart.employee.group.FunctionGroup;
import cs_orgchart.employee.override.EmployeeOverride;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Database employees + overlay → the employees of the site (spec v3 §10.1 as amended for Phase 5).
 */
class EmployeeDtoAssemblerTest {

    private final FunctionResolver functions = new FunctionResolver(SpecDepartmentDictionaries.INSTANCE);
    private final EmployeeDtoAssembler pmFirst = new EmployeeDtoAssembler(functions, ParentSource.PM);
    private final EmployeeDtoAssembler managerFirst = new EmployeeDtoAssembler(functions, ParentSource.MANAGER);

    private final List<Employee> employees = new ArrayList<>();
    private final List<EmployeeOverride> overlay = new ArrayList<>();
    private final List<FunctionGroup> groups = new ArrayList<>();
    private final Set<Long> hidden = new HashSet<>();
    private long nextId = 1;

    private Employee person(String login, String title, String level2, String level3) {
        Employee e = Employee.builder().id(nextId++).adGuid(UUID.randomUUID())
                .dn("CN=" + login + ",OU=KZ,OU=Countries,DC=corp,DC=example").login(login).upn(login + "@kz.example")
                .email(login + ".mail@kz.example").displayName("Person, " + login).jobTitle(title)
                .deptLevel1("Central Services").deptLevel2(level2).deptLevel3(level3).countryCode("KZ")
                .phone("+000 1").mobile("+000 2").office("Office").build();
        employees.add(e);
        return e;
    }

    private Employee external(String login) {
        Employee e = person(login, "Managing Partner", null, null);
        e.setExternal(true);
        e.setCountryCode(null);
        e.setDeptLevel1(null);
        return e;
    }

    private EmployeeOverride membership(Employee e, String cs) {
        EmployeeOverride o = EmployeeOverride.builder().id(nextId++).employeeId(e.getId()).cs(cs).build();
        overlay.add(o);
        return o;
    }

    /** A group object of the function (U4); returns its id */
    private Long group(String cs, String name, int sortOrder) {
        FunctionGroup g = FunctionGroup.builder().id(nextId++).cs(cs).name(name).sortOrder(sortOrder).build();
        groups.add(g);
        return g.getId();
    }

    private List<cs_orgchart.model.Employee> assemble() {
        return pmFirst.assemble(employees, overlay, groups, hidden, Map.of());
    }

    private static cs_orgchart.model.Employee only(List<cs_orgchart.model.Employee> rows, String name, String cs) {
        return rows.stream().filter(r -> r.getName().equals(name) && r.getCs().equals(cs)).findFirst().orElseThrow();
    }

    // --- without overlay -----------------------------------------------------------------------------------------

    @Test
    void personWithoutOverlayIsShownOnceInTheFunctionOfTheirDepartment() {
        Employee e = person("tenders", "Tenders Manager", "Facilities & Operations", "Proposals & Tenders");

        cs_orgchart.model.Employee row = assemble().get(0);

        assertThat(row.getName()).isEqualTo("Person, tenders");
        assertThat(row.getEmail()).isEqualTo("tenders.mail@kz.example");
        assertThat(row.getCs()).isEqualTo("Tenders");
        assertThat(row.getGroup()).as("default group: level 3").isEqualTo("Proposals & Tenders");
        assertThat(row.getJobTitle()).isEqualTo("Tenders Manager");
        assertThat(row.getGradeOrder()).isEqualTo(4);
        assertThat(row.getPhotoUrl()).isEqualTo("/photos/default.jpg");
        assertThat(row.getAreaOfDuties()).isNull();
        assertThat(row.getJobDescription()).isNull();
        assertThat(row.getId()).isEqualTo(e.getId());
        assertThat(row.getCountryCode()).isEqualTo("KZ");
    }

    @Test
    void generalNodeGivesNoDefaultGroupAndLevelFourWins() {
        person("general", "IT Specialist", "ITS", "ITS General");
        Employee ops = person("ops", "IT Specialist", "ITS", "ITS General");
        ops.setDeptLevel4("IT Operations");

        List<cs_orgchart.model.Employee> rows = assemble();

        assertThat(only(rows, "Person, general", "ITS").getGroup()).isNull();
        assertThat(only(rows, "Person, ops", "ITS").getGroup()).isEqualTo("IT Operations");
    }

    @Test
    void executivesAndProcurementAreInFandO() {
        person("exec", "Office Manager", "Executives", "Administration Office");
        person("buyer", "Buyer", "Procurement", "Procurement General");

        assertThat(assemble()).extracting(cs_orgchart.model.Employee::getCs).containsOnly("F&O");
    }

    @Test
    void upnIsTheEmailWhenAdHasNoMail() {
        Employee e = person("nomail", "Specialist", "QRM", "QRM General");
        e.setEmail(null);

        assertThat(assemble().get(0).getEmail()).isEqualTo("nomail@kz.example");
    }

    @Test
    void photoIsFoundByEmail() {
        person("photo", "Specialist", "QRM", "QRM General");

        assertThat(pmFirst.assemble(employees, overlay, Map.of("photo.mail@kz.example.jpg", "photo.mail@kz.example.jpg"))
                .get(0).getPhotoUrl()).isEqualTo("/photos/photo.mail@kz.example.jpg");
    }

    @Test
    void disabledAndExternalPeopleAreNotListed() {
        person("active", "Specialist", "QRM", "QRM General");
        person("leaver", "Specialist", "QRM", "QRM General").setEnabled(false);
        external("partner");

        assertThat(assemble()).extracting(cs_orgchart.model.Employee::getName).containsExactly("Person, active");
    }

    // --- overlay -------------------------------------------------------------------------------------------------

    @Test
    void personInTwoFunctionsIsShownOncePerOverlayRow() {
        Employee boss = person("boss", "Head of People", "People", "People General");
        Employee fo = person("fohead", "Head of Facilities", "Facilities & Operations", "F&O General");
        Employee both = person("both", "HR Administration Manager", "People", "HR Administration");
        both.setPmId(boss.getId());
        EmployeeOverride people = membership(both, "People");
        people.setGroupId(group("People", "HR Administration", 10));
        people.setAreaOfDuties("HR administration");
        EmployeeOverride office = membership(both, "F&O");
        office.setGroupId(group("F&O", "Office support", 20));
        office.setJobDescription("Access cards");
        office.setManagerOverrideId(fo.getId());

        List<cs_orgchart.model.Employee> rows = assemble().stream().filter(r -> r.getName().equals("Person, both"))
                .toList();

        assertThat(rows).extracting(cs_orgchart.model.Employee::getCs).containsExactlyInAnyOrder("People", "F&O");
        assertThat(rows).extracting(cs_orgchart.model.Employee::getId).containsOnly(both.getId());
        cs_orgchart.model.Employee inPeople = only(rows, "Person, both", "People");
        assertThat(inPeople.getGroup()).isEqualTo("HR Administration");
        assertThat(inPeople.getAreaOfDuties()).isEqualTo("HR administration");
        assertThat(inPeople.getPm()).isEqualTo("Person, boss");
        assertThat(inPeople.getParentId()).isEqualTo(boss.getId());
        cs_orgchart.model.Employee inFo = only(rows, "Person, both", "F&O");
        assertThat(inFo.getGroup()).isEqualTo("Office support");
        assertThat(inFo.getJobDescription()).isEqualTo("Access cards");
        assertThat(inFo.getAreaOfDuties()).isNull();
        assertThat(inFo.getPm()).as("manager override of this function only").isEqualTo("Person, fohead");
        assertThat(inFo.getParentId()).isEqualTo(fo.getId());
    }

    @Test
    void personWithOverlayRowsIsNotShownAgainInTheAdFunction() {
        Employee e = person("moved", "Specialist", "QRM", "QRM General");
        membership(e, "OGC");

        assertThat(assemble()).extracting(cs_orgchart.model.Employee::getCs).containsExactly("OGC");
    }

    @Test
    void defaultGroupOnlyForTheAdFunction() {
        Employee e = person("twice", "Specialist", "Facilities & Operations", "Proposals & Tenders");
        membership(e, "Tenders");
        membership(e, "People");

        List<cs_orgchart.model.Employee> rows = assemble();

        assertThat(only(rows, "Person, twice", "Tenders").getGroup()).isEqualTo("Proposals & Tenders");
        assertThat(only(rows, "Person, twice", "People").getGroup()).isNull();
    }

    @Test
    void headsComeFirstInTheirFunctionByHeadRankWithGroupHead() {
        Employee chief = person("chief", "Chief Information Officer", "ITS", "ITS General");
        Employee second = person("second", "IT Specialist", "ITS", "ITS General");
        Employee third = person("third", "Senior Manager", "ITS", "ITS General");
        person("member", "Head of Something", "ITS", "ITS General");
        membership(third, "ITS").setHeadRank((short) 3);
        membership(chief, "ITS").setHeadRank((short) 2);
        EmployeeOverride rankOne = membership(second, "ITS");
        rankOne.setHeadRank((short) 1);
        rankOne.setGroupName("ignored for a head");
        person("other", "Specialist", "QRM", "QRM General");

        List<cs_orgchart.model.Employee> its = assemble().stream().filter(r -> r.getCs().equals("ITS")).toList();

        assertThat(its).extracting(cs_orgchart.model.Employee::getName)
                .containsExactly("Person, second", "Person, chief", "Person, third", "Person, member");
        assertThat(its.subList(0, 3)).extracting(cs_orgchart.model.Employee::getGroup).containsOnly("HEAD");
        assertThat(its.get(3).getGroup()).isNull();
    }

    // --- parents -------------------------------------------------------------------------------------------------

    @Test
    void externalParentAppearsOnlyInThePmFields() {
        Employee partner = external("partner");
        partner.setEmail(null);
        Employee head = person("head", "Head of QRM", "QRM", "QRM General");
        head.setPmId(partner.getId());
        head.setManagerId(partner.getId());

        cs_orgchart.model.Employee row = assemble().get(0);

        assertThat(row.getPm()).isEqualTo("Person, partner");
        assertThat(row.getPmEmail()).isEqualTo("partner@kz.example");
        assertThat(row.getPmJobTitle()).isEqualTo("Managing Partner");
        assertThat(row.getParentId()).isNull();
    }

    @Test
    void orgParentSourceDecidesAndTheOtherRelationIsTheFallback() {
        Employee pm = person("pm", "Manager", "QRM", "QRM General");
        Employee line = person("line", "Manager", "QRM", "QRM General");
        Employee both = person("both", "Specialist", "QRM", "QRM General");
        both.setPmId(pm.getId());
        both.setManagerId(line.getId());
        Employee lineOnly = person("lineonly", "Specialist", "QRM", "QRM General");
        lineOnly.setManagerId(line.getId());
        Employee pmOnly = person("pmonly", "Specialist", "QRM", "QRM General");
        pmOnly.setPmId(pm.getId());

        List<cs_orgchart.model.Employee> byPm = pmFirst.assemble(employees, overlay, Map.of());
        List<cs_orgchart.model.Employee> byManager = managerFirst.assemble(employees, overlay, Map.of());

        assertThat(only(byPm, "Person, both", "QRM").getParentId()).isEqualTo(pm.getId());
        assertThat(only(byManager, "Person, both", "QRM").getParentId()).isEqualTo(line.getId());
        assertThat(only(byPm, "Person, lineonly", "QRM").getParentId()).as("fallback").isEqualTo(line.getId());
        assertThat(only(byManager, "Person, pmonly", "QRM").getParentId()).as("fallback").isEqualTo(pm.getId());
    }

    @Test
    void disabledParentCountsAsNoneAndTheFallbackIsUsed() {
        Employee leaver = person("leaver", "Manager", "QRM", "QRM General");
        leaver.setEnabled(false);
        Employee line = person("line", "Manager", "QRM", "QRM General");
        Employee e = person("e", "Specialist", "QRM", "QRM General");
        e.setPmId(leaver.getId());
        e.setManagerId(line.getId());

        assertThat(only(assemble(), "Person, e", "QRM").getParentId()).isEqualTo(line.getId());
    }

    @Test
    void personWithoutManagerHasNoParent() {
        person("root", "Chief Financial Officer", "Finance", "Finance General");

        cs_orgchart.model.Employee row = assemble().get(0);

        assertThat(row.getPm()).isNull();
        assertThat(row.getPmEmail()).isNull();
        assertThat(row.getPmJobTitle()).isNull();
        assertThat(row.getParentId()).isNull();
    }

    @Test
    void ownIdAsParentIsIgnored() {
        Employee e = person("self", "Specialist", "QRM", "QRM General");
        e.setPmId(e.getId());

        assertThat(assemble().get(0).getParentId()).isNull();
    }

    // --- groups and hidden people (U4) ---------------------------------------------------------------------------

    @Test
    void theGroupComesFromTheGroupObjectNotFromTheOldColumn() {
        Employee e = person("e", "Specialist", "ITS", "IT PMO");
        EmployeeOverride row = membership(e, "ITS");
        row.setGroupName("stale old value");
        assertThat(only(assemble(), "Person, e", "ITS").getGroup()).as("group_name is not read").isEqualTo("IT PMO");

        row.setGroupId(group("ITS", "Platform", 30));
        cs_orgchart.model.Employee dto = only(assemble(), "Person, e", "ITS");
        assertThat(dto.getGroup()).isEqualTo("Platform");
        assertThat(dto.getGroupOrder()).isEqualTo(30);
    }

    @Test
    void theAdDefaultGroupTakesTheOrderOfItsAutoGroup() {
        person("e", "Specialist", "ITS", "IT PMO");
        group("ITS", "it pmo", 50);
        cs_orgchart.model.Employee dto = only(assemble(), "Person, e", "ITS");
        assertThat(dto.getGroup()).isEqualTo("it pmo");
        assertThat(dto.getGroupOrder()).isEqualTo(50);
    }

    @Test
    void hiddenPeopleAreLeftOutAndTheirCounseleesGetTheNextVisibleManager() {
        Employee top = person("top", "Head of QRM", "QRM", "QRM General");
        Employee middle = person("middle", "Manager", "QRM", "QRM General");
        Employee lower = person("lower", "Lead", "QRM", "QRM General");
        Employee e = person("e", "Specialist", "QRM", "QRM General");
        middle.setPmId(top.getId());
        lower.setPmId(middle.getId());
        e.setPmId(lower.getId());
        hidden.add(middle.getId());
        hidden.add(lower.getId());

        List<cs_orgchart.model.Employee> rows = assemble();
        assertThat(rows).extracting(cs_orgchart.model.Employee::getName).containsExactlyInAnyOrder("Person, top", "Person, e");
        assertThat(only(rows, "Person, e", "QRM").getParentId()).as("next visible manager").isEqualTo(top.getId());
        assertThat(only(rows, "Person, e", "QRM").getPm()).isEqualTo("Person, top");
    }

    @Test
    void withoutAVisibleManagerTheCounseleeBecomesARoot() {
        Employee boss = person("boss", "Manager", "QRM", "QRM General");
        Employee e = person("e", "Specialist", "QRM", "QRM General");
        e.setPmId(boss.getId());
        boss.setPmId(e.getId());
        hidden.add(boss.getId());

        cs_orgchart.model.Employee row = only(assemble(), "Person, e", "QRM");
        assertThat(row.getParentId()).as("the cycle through the hidden boss leads back to the person").isNull();
        assertThat(row.getPm()).isNull();
    }

    // --- JSON ----------------------------------------------------------------------------------------------------

    @Test
    void jsonHasThePreviousFieldsPlusIdParentIdCountryCodeAndNoPhoneMobileOrOffice() throws Exception {
        Employee boss = person("boss", "Head of QRM", "QRM", "QRM General");
        person("e", "Specialist", "QRM", "QRM General").setPmId(boss.getId());

        String json = new ObjectMapper().writeValueAsString(assemble());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = new ObjectMapper().readValue(json, List.class);
        assertThat(rows.get(1)).containsOnlyKeys("name", "cs", "group", "jobTitle", "email", "pm", "pmEmail",
                "pmJobTitle", "photoUrl", "gradeOrder", "areaOfDuties", "jobDescription", "id", "parentId",
                "countryCode", "pmId", "pmExternal");
        assertThat(json).doesNotContain("+000", "Office\"", "phone", "mobile", "office");
    }
}
