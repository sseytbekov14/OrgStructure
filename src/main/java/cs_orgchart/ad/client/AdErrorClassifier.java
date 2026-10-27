package cs_orgchart.ad.client;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an LDAP failure (JNDI, Spring LDAP, TLS, socket) into an {@link AdDirectoryException} with a distinct,
 * actionable message (spec v3 §7). The checks run over the whole cause chain, most specific first.
 */
public final class AdErrorClassifier {

    /** AD's sub-code of LDAP error 49, e.g. "AcceptSecurityContext error, data 52e, v4563" */
    private static final Pattern AD_BIND_DATA = Pattern.compile("data ([0-9a-fA-F]{3})");

    private static final Map<String, String> AD_BIND_REASONS = Map.of(
            "525", "the account does not exist",
            "52e", "wrong user name or password",
            "530", "logon not permitted at this time",
            "531", "logon not permitted from this workstation",
            "532", "the password has expired",
            "533", "the account is disabled",
            "701", "the account has expired",
            "773", "the password must be reset",
            "775", "the account is LOCKED OUT");

    private AdErrorClassifier() {
    }

    /**
     * @param target the host:port of the domain controller, for the message
     */
    public static AdDirectoryException classify(Throwable failure, String target) {
        if (failure instanceof AdDirectoryException known) {
            return known;
        }
        String detail = rootDetail(failure);

        if (any(failure, t -> t instanceof javax.naming.AuthenticationException
                || t instanceof org.springframework.ldap.AuthenticationException
                || contains(t, "error code 49"))) {
            Matcher data = AD_BIND_DATA.matcher(allMessages(failure));
            String reason = data.find()
                    ? AD_BIND_REASONS.getOrDefault(data.group(1).toLowerCase(Locale.ROOT), "AD sub-code " + data.group(1))
                    : "invalid credentials";
            return new AdDirectoryException(AdErrorCode.AUTH, "invalid credentials (LDAP error 49: " + reason
                    + "). Check AD_LDAP_BIND_DN (UPN form user@domain, or the full DN) and AD_LDAP_BIND_PASSWORD."
                    + " Scheduled and startup syncs are suspended until a manual sync or a restart, so that the"
                    + " account is not locked by repeated attempts.", failure);
        }
        if (any(failure, t -> contains(t, "No subject alternative") || contains(t, "No name matching"))) {
            return new AdDirectoryException(AdErrorCode.TLS, "the certificate of " + target + " does not name this"
                    + " host: use the host name the certificate is issued to (the full DNS name of the domain"
                    + " controller) in AD_LDAP_URL. " + detail, failure);
        }
        if (any(failure, t -> contains(t, "PKIX path building failed")
                || t instanceof java.security.cert.CertPathBuilderException)) {
            return new AdDirectoryException(AdErrorCode.TLS, "the certificate of " + target + " is not trusted"
                    + " (PKIX path building failed): truststore missing or wrong, see spec section 8.1"
                    + " (AD_LDAP_TRUSTSTORE_PATH with the corporate root and intermediate CA,"
                    + " AD_LDAP_TRUSTSTORE_PASSWORD).", failure);
        }
        if (any(failure, t -> t instanceof SSLException || t instanceof CertificateException)) {
            return new AdDirectoryException(AdErrorCode.TLS, "TLS connection to " + target + " failed: " + detail
                    + ". AD_LDAP_URL must be ldaps://<domain controller>:636; for the certificate see spec section"
                    + " 8.1.", failure);
        }
        if (any(failure, t -> t instanceof javax.naming.TimeLimitExceededException
                || t instanceof org.springframework.ldap.TimeLimitExceededException
                || contains(t, "read timed out"))) {
            return new AdDirectoryException(AdErrorCode.TIMEOUT, target + " accepted the connection but did not"
                    + " answer in time (" + detail + "). Try again later; if it persists, check the load of the"
                    + " domain controller or raise AD_LDAP_READ_TIMEOUT.", failure);
        }
        if (any(failure, t -> t instanceof UnknownHostException)) {
            return new AdDirectoryException(AdErrorCode.NETWORK, "host of " + target + " not found in DNS ("
                    + detail + "): check AD_LDAP_URL and the DNS of this machine.", failure);
        }
        if (any(failure, t -> t instanceof ConnectException || t instanceof NoRouteToHostException
                || t instanceof SocketTimeoutException
                || t instanceof javax.naming.CommunicationException
                || t instanceof javax.naming.ServiceUnavailableException
                || t instanceof org.springframework.ldap.CommunicationException
                || t instanceof org.springframework.ldap.ServiceUnavailableException)) {
            return new AdDirectoryException(AdErrorCode.NETWORK, "cannot reach " + target + " (" + detail + "):"
                    + " is port 636 open from this machine to the domain controller? Check with"
                    + " Test-NetConnection <host> -Port 636 (Windows) or nc -zv <host> 636 (Linux).", failure);
        }
        if (any(failure, t -> t instanceof javax.naming.NoPermissionException
                || t instanceof org.springframework.ldap.NoPermissionException
                || contains(t, "error code 50") || contains(t, "000004DC"))) {
            return new AdDirectoryException(AdErrorCode.PERMISSION, "insufficient rights: the bind account may not"
                    + " read the users under AD_LDAP_BASE_DN (" + detail + "). It needs read access to"
                    + " OU=Countries,OU=CCG including kPMG-User-PerformanceManagerUPN.", failure);
        }
        if (any(failure, t -> t instanceof javax.naming.SizeLimitExceededException
                || t instanceof org.springframework.ldap.SizeLimitExceededException)) {
            return new AdDirectoryException(AdErrorCode.LIMIT, "search size limit exceeded (" + detail + "): paging"
                    + " did not work; AD_LDAP_PAGE_SIZE must be between 1 and 1000.", failure);
        }
        return new AdDirectoryException(AdErrorCode.LDAP, "LDAP request to " + target + " failed: " + detail,
                failure);
    }

    private static boolean any(Throwable failure, Predicate<Throwable> test) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            if (test.test(t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(Throwable t, String text) {
        String message = t.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT));
    }

    private static String allMessages(Throwable failure) {
        StringBuilder all = new StringBuilder();
        any(failure, t -> {
            all.append(t.getMessage()).append('\n');
            return false;
        });
        return all.toString();
    }

    /** Class and message of the innermost cause, e.g. "ConnectException: Connection refused" */
    private static String rootDetail(Throwable failure) {
        Throwable root = failure;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (root.getCause() != null && seen.add(root)) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }
}
