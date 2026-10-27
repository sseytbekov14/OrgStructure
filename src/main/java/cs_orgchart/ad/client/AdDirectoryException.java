package cs_orgchart.ad.client;

import lombok.Getter;

/**
 * An AD read that failed, with an actionable message (spec v2.1 §7) and its {@link AdErrorCode}. Messages never
 * contain the bind password.
 */
@Getter
public class AdDirectoryException extends RuntimeException {

    private final AdErrorCode code;

    public AdDirectoryException(AdErrorCode code, String message) {
        this(code, message, null);
    }

    public AdDirectoryException(AdErrorCode code, String message, Throwable cause) {
        super(code + ": " + message, cause);
        this.code = code;
    }
}
