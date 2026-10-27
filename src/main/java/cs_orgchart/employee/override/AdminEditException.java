package cs_orgchart.employee.override;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * An admin edit that is refused, with the HTTP status of the answer and a code for the page.
 */
@Getter
public class AdminEditException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AdminEditException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static AdminEditException badRequest(String code, String message) {
        return new AdminEditException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static AdminEditException conflict(String code, String message) {
        return new AdminEditException(HttpStatus.CONFLICT, code, message);
    }

    public static AdminEditException notFound(String code, String message) {
        return new AdminEditException(HttpStatus.NOT_FOUND, code, message);
    }
}
