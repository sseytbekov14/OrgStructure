package cs_orgchart.employee.web;

import cs_orgchart.access.AdminsController;
import cs_orgchart.employee.override.AdminEditException;
import cs_orgchart.employee.override.FieldValidationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The answers of refused admin edits: {"status", "code", "message"}. A change made at the same time by someone else
 * (version or unique index) is 409.
 */
@RestControllerAdvice(assignableTypes = {OverrideAdminController.class, FunctionHeadsAdminController.class,
        AdminsController.class, GroupsAdminController.class, VisibilityAdminController.class})
public class AdminEditErrors {

    @ExceptionHandler(AdminEditException.class)
    public ResponseEntity<Map<String, Object>> refused(AdminEditException e) {
        return answer(e.getStatus(), e.getCode(), e.getMessage());
    }

    /** 400 with every refused field: {"errors": {field: {code, message}}} (U3) */
    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<Map<String, Object>> invalidFields(FieldValidationException e) {
        Map<String, Object> errors = new LinkedHashMap<>();
        e.getProblems().forEach((field, problem) ->
                errors.put(field, Map.of("code", problem.code(), "message", problem.message())));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("code", "VALIDATION");
        body.put("message", "Some fields are not valid.");
        body.put("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler({ObjectOptimisticLockingFailureException.class, DataIntegrityViolationException.class})
    public ResponseEntity<Map<String, Object>> concurrent(RuntimeException e) {
        return answer(HttpStatus.CONFLICT, "CHANGED", "someone else changed this meanwhile; reload the page");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return answer(HttpStatus.BAD_REQUEST, "BODY_UNREADABLE", "the request body is not valid JSON");
    }

    private static ResponseEntity<Map<String, Object>> answer(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("status", status.value(), "code", code, "message", message));
    }
}
