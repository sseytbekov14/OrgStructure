package cs_orgchart.employee.override;

import cs_orgchart.employee.override.FieldRules.Problem;
import lombok.Getter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Refused field values of an admin request: answered 400 with {"errors": {field: {code, message}}} (U3), so the page
 * shows each message next to its field.
 */
@Getter
public class FieldValidationException extends RuntimeException {

    private final transient Map<String, Problem> problems;

    public FieldValidationException(Map<String, Problem> problems) {
        super("invalid fields: " + problems.keySet());
        this.problems = Collections.unmodifiableMap(new LinkedHashMap<>(problems));
    }
}
