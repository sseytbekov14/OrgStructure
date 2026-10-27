package cs_orgchart.employee;

import cs_orgchart.model.Employee;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The employees the site shows, in memory (spec v3 §10.1): replaced as a whole by ExcelService
 * (EMPLOYEE_SOURCE=excel) or DbEmployeeDirectory (EMPLOYEE_SOURCE=db). Search, functions and the employees of a
 * function work as they did in ExcelService.
 */
@Component
public class EmployeeDirectory {

    private volatile List<Employee> employees = List.of();

    private Comparator<Employee> employeeHierarchyComparator() {
        return Comparator
                .comparingInt(Employee::getGradeOrder)
                .thenComparing(Employee::getJobTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(Employee::getName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    /** The new list, published at once */
    public void replace(List<Employee> loaded) {
        employees = List.copyOf(loaded);
    }

    public List<Employee> getAllEmployees() {
        return new ArrayList<>(employees);
    }

    public List<String> getFunctions() {
        return employees.stream()
                .map(Employee::getCs)
                .filter(cs -> cs != null && !cs.trim().isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public List<Employee> search(String query) {
        if (query == null || query.trim().isEmpty()) {
            return new ArrayList<>();
        }

        String lowerQuery = query.toLowerCase().trim();
        boolean searchPmOnly = false;
        String searchTarget = lowerQuery;

        if (lowerQuery.startsWith("pm:") || lowerQuery.startsWith("pm ")) {
            searchPmOnly = true;
            searchTarget = lowerQuery.substring(3).trim();
        } else if (lowerQuery.startsWith("manager:") || lowerQuery.startsWith("manager ")) {
            searchPmOnly = true;
            searchTarget = lowerQuery.substring(8).trim();
        }

        String cleanQuery = searchTarget.replaceAll("[^\\p{L}\\p{N}\\s]", " ");
        String[] tokens = cleanQuery.trim().split("\\s+");
        if (tokens.length == 0 || (tokens.length == 1 && tokens[0].isEmpty())) {
            return new ArrayList<>();
        }

        final boolean finalSearchPmOnly = searchPmOnly;
        return employees.stream()
            .filter(e -> {
                String searchableText;
                if (finalSearchPmOnly) {
                    searchableText = String.join(" ",
                        e.getPm() != null ? e.getPm() : "",
                        e.getPmEmail() != null ? e.getPmEmail() : "",
                        e.getPmJobTitle() != null ? e.getPmJobTitle() : ""
                    ).toLowerCase();
                } else {
                    searchableText = String.join(" ",
                        e.getName() != null ? e.getName() : "",
                        e.getEmail() != null ? e.getEmail() : "",
                        e.getCs() != null ? e.getCs() : "",
                        e.getGroup() != null ? e.getGroup() : "",
                        e.getAreaOfDuties() != null ? e.getAreaOfDuties() : "",
                        e.getJobDescription() != null ? e.getJobDescription() : "",
                        e.getJobTitle() != null ? e.getJobTitle() : ""
                    ).toLowerCase();
                }

                String cleanSearchableText = searchableText.replaceAll("[^\\p{L}\\p{N}\\s]", " ");

                for (String token : tokens) {
                    if (!cleanSearchableText.contains(token) && !searchableText.contains(token)) {
                        return false;
                    }
                }
                return true;
            })
            .sorted(employeeHierarchyComparator())
            .collect(Collectors.toList());
    }

    public List<Employee> getEmployeesByFunction(String cs) {
        return employees.stream()
                .filter(e -> e.getCs() != null && e.getCs().equals(cs))
                .sorted(employeeHierarchyComparator())
                .collect(Collectors.toList());
    }

    /**
     * Only the employees of this country (country code of the DN, db mode); a null or empty country keeps all.
     * Excel employees have no country code.
     */
    public static List<Employee> inCountry(List<Employee> list, String country) {
        if (country == null || country.isBlank()) {
            return list;
        }
        return list.stream()
                .filter(e -> e.getCountryCode() != null && e.getCountryCode().equalsIgnoreCase(country.trim()))
                .collect(Collectors.toList());
    }
}
