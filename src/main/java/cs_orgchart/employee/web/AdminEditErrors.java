package cs_orgchart.employee.web;

import cs_orgchart.access.AdminsController;
import cs_orgchart.employee.override.AdminEditException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * The answers of refused admin edits: {"status", "code", "message"}. A change made at the same time by someone else
 * (version or unique index) is 409.
 */
@RestControllerAdvice(assignableTypes = {OverrideAdminController.class, FunctionHeadsAdminController.class,
        AdminsController.class})
public class AdminEditErrors {

    @ExceptionHandler(AdminEditException.class)
    public ResponseEntity<Map<String, Object>> refused(AdminEditException e) {
        return answer(e.getStatus(), e.getCode(), e.getMessage());
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
