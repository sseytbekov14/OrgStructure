package cs_orgchart.employee;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/**
 * Where the employees of the site come from (spec v3 §8): EMPLOYEE_SOURCE=excel (the Excel file, as before) or db
 * (the employees synced from AD), and which AD relation is the parent in the tree, ORG_PARENT_SOURCE=pm|manager
 * (the other one is the fallback). Switching EMPLOYEE_SOURCE back to excel is the rollback.
 */
@Data
@ConfigurationProperties("app.employees")
public class EmployeeSourceProperties {

    public enum Source { EXCEL, DB }

    public enum ParentSource { PM, MANAGER }

    /** EMPLOYEE_SOURCE */
    private String source = "excel";
    /** ORG_PARENT_SOURCE */
    private String parentSource = "pm";

    /** A typo must not silently pick a data source: the start stops with the allowed values */
    @PostConstruct
    void check() {
        source();
        orgParentSource();
    }

    public Source source() {
        return parse(Source.class, source, "EMPLOYEE_SOURCE", "excel or db");
    }

    public ParentSource orgParentSource() {
        return parse(ParentSource.class, parentSource, "ORG_PARENT_SOURCE", "pm or manager");
    }

    public boolean isDb() {
        return source() == Source.DB;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String key, String allowed) {
        try {
            return Enum.valueOf(type, String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(key + "=" + value + " is not known: use " + allowed);
        }
    }
}
