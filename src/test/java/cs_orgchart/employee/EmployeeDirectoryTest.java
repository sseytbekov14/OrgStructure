package cs_orgchart.employee;

import cs_orgchart.model.Employee;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Search, functions and the employees of a function, as they worked in ExcelService (moved without changes).
 */
class EmployeeDirectoryTest {

    private final EmployeeDirectory directory = new EmployeeDirectory();

    private static Employee employee(String name, String cs, String group, String title, String pm, String country) {
        return Employee.builder().name(name).cs(cs).group(group).jobTitle(title).email(name.toLowerCase() + "@corp.example")
                .pm(pm).pmEmail(pm == null ? null : pm.toLowerCase() + "@corp.example").pmJobTitle(pm == null ? null : "Chief")
                .gradeOrder(GradeOrder.of(title)).areaOfDuties("Duties of " + name).jobDescription("Tasks")
                .countryCode(country).build();
    }

    @BeforeEach
    void fill() {
        directory.replace(List.of(
                employee("Zeta", "ITS", "Service Desk", "IT Specialist", "Alpha", "KZ"),
                employee("Alpha", "ITS", "HEAD", "Chief Information Officer", null, "KZ"),
                employee("Beta", "ITS", "PMO", "IT Project Manager", "Alpha", "UZ"),
                employee("Gamma", "Finance", "Accounting", "Senior Accountant", "Delta", "GE"),
                employee("Delta", "Finance", "HEAD", "Chief Financial Officer", null, null),
                employee("Blank", " ", null, null, null, null)));
    }

    @Test
    void functionsAreDistinctSortedAndWithoutBlanks() {
        assertThat(directory.getFunctions()).containsExactly("Finance", "ITS");
    }

    @Test
    void employeesOfAFunctionAreSortedByGradeThenTitleThenName() {
        assertThat(directory.getEmployeesByFunction("ITS")).extracting(Employee::getName)
                .containsExactly("Alpha", "Beta", "Zeta");
        assertThat(directory.getEmployeesByFunction("its")).as("exact function name").isEmpty();
    }

    @Test
    void searchNeedsEveryTokenInAnyField() {
        assertThat(directory.search("it specialist")).extracting(Employee::getName).containsExactly("Zeta");
        assertThat(directory.search("duties beta")).extracting(Employee::getName).containsExactly("Beta");
        assertThat(directory.search("service-desk")).extracting(Employee::getName).containsExactly("Zeta");
        assertThat(directory.search("its")).extracting(Employee::getName).containsExactly("Alpha", "Beta", "Zeta");
    }

    @Test
    void pmPrefixSearchesTheManagerFieldsOnly() {
        assertThat(directory.search("pm: alpha")).extracting(Employee::getName).containsExactly("Beta", "Zeta");
        assertThat(directory.search("manager delta")).extracting(Employee::getName).containsExactly("Gamma");
        assertThat(directory.search("pm: chief")).hasSize(3);
    }

    @Test
    void emptyQueryFindsNothing() {
        assertThat(directory.search(null)).isEmpty();
        assertThat(directory.search("  ")).isEmpty();
        assertThat(directory.search("!!")).isEmpty();
    }

    @Test
    void replaceSwapsTheWholeListAndCopiesAreIndependent() {
        List<Employee> copy = directory.getAllEmployees();
        copy.clear();
        assertThat(directory.getAllEmployees()).hasSize(6);

        directory.replace(List.of(employee("Only", "QRM", null, "Risk Manager", null, "KZ")));
        assertThat(directory.getFunctions()).containsExactly("QRM");
    }

    @Test
    void countryFilterKeepsOnlyThatCountryAndNoneKeepsAll() {
        List<Employee> all = directory.getAllEmployees();

        assertThat(EmployeeDirectory.inCountry(all, "uz")).extracting(Employee::getName).containsExactly("Beta");
        assertThat(EmployeeDirectory.inCountry(all, null)).hasSize(6);
        assertThat(EmployeeDirectory.inCountry(all, " ")).hasSize(6);
        assertThat(EmployeeDirectory.inCountry(all, "AM")).isEmpty();
    }
}
