package cs_orgchart.employee;

/**
 * Sort order of an employee by job title (smaller first), the same for Excel and database employees.
 */
public final class GradeOrder {

    private GradeOrder() {
    }

    public static int of(String jobTitle) {
        if (jobTitle == null) return 99;
        String title = jobTitle.toLowerCase();
        if (title.contains("chief") || title.contains("head") || title.contains("leader")) return 1;
        if (title.contains("deputy")) return 2;
        if (title.contains("senior") && title.contains("manager")) return 3;
        if (title.contains("manager")) return 4;
        if (title.contains("supervisor")) return 5;
        if (title.contains("senior")) return 5;
        if (title.contains("junior")) return 7;
        if (title.contains("intern")) return 8;
        if (title.contains("trainee")) return 9;
        return 6;
    }
}
