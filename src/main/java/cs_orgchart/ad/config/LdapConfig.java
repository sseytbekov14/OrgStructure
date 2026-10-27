package cs_orgchart.ad.config;

import cs_orgchart.ad.client.AdDirectoryException;
import cs_orgchart.ad.client.AdErrorCode;
import cs_orgchart.ad.client.LdapAdDirectoryClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ldap.core.support.LdapContextSource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.net.URI;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The LDAP connection of AD_SYNC_MODE=ldap (spec v2.1 §4.1, §8.1): LDAPS only, objectGUID as binary, connect and read
 * timeouts, TLS trust from AD_LDAP_TRUSTSTORE_PATH for this connection only. Building it never connects: the first
 * bind happens at the first sync. Unusable settings do not stop the application: every AD read then fails with
 * {@link AdErrorCode#CONFIG} (or {@link AdErrorCode#TLS} for the truststore) and the message says what to fix.
 */
@Slf4j
public final class LdapConfig {

    static final String BINARY_ATTRIBUTES = "java.naming.ldap.attributes.binary";
    static final String SOCKET_FACTORY = "java.naming.ldap.factory.socket";
    static final String CONNECT_TIMEOUT = "com.sun.jndi.ldap.connect.timeout";
    static final String READ_TIMEOUT = "com.sun.jndi.ldap.read.timeout";

    private LdapConfig() {
    }

    /** The AD client of AD_SYNC_MODE=ldap */
    public static LdapAdDirectoryClient client(AdLdapProperties.Ldap settings) {
        List<String> problems = problems(settings);
        if (!problems.isEmpty()) {
            AdDirectoryException reason = new AdDirectoryException(AdErrorCode.CONFIG,
                    "AD_SYNC_MODE=ldap but " + String.join("; ", problems));
            log.warn("AD sync cannot read AD: {}", reason.getMessage());
            return LdapAdDirectoryClient.unusable(reason, settings);
        }
        try {
            return new LdapAdDirectoryClient(contextSource(settings), settings);
        } catch (AdDirectoryException e) {
            log.warn("AD sync cannot read AD: {}", e.getMessage());
            return LdapAdDirectoryClient.unusable(e, settings);
        }
    }

    /** What is missing or not allowed in the AD_LDAP_* settings (empty: they can be used) */
    static List<String> problems(AdLdapProperties.Ldap settings) {
        List<String> problems = new ArrayList<>();
        String url = settings.getUrl() == null ? "" : settings.getUrl().trim();
        if (url.isEmpty()) {
            problems.add("AD_LDAP_URL is not set (ldaps://<domain controller>:636)");
        } else if (!url.toLowerCase(Locale.ROOT).startsWith("ldaps://")) {
            problems.add("AD_LDAP_URL must start with ldaps:// (plain LDAP on port 389 is not allowed)");
        } else if (host(url) == null) {
            problems.add("AD_LDAP_URL has no host name (ldaps://<domain controller>:636)");
        }
        if (blank(settings.getBaseDn())) {
            problems.add("AD_LDAP_BASE_DN is not set");
        }
        if (blank(settings.getBindDn())) {
            problems.add("AD_LDAP_BIND_DN is not set (UPN form user@domain, or the full DN)");
        }
        if (blank(settings.getBindPassword())) {
            problems.add("AD_LDAP_BIND_PASSWORD is not set");
        }
        if (settings.getPageSize() < 1 || settings.getPageSize() > 1000) {
            problems.add("AD_LDAP_PAGE_SIZE must be between 1 and 1000");
        }
        return problems;
    }

    /**
     * The connection settings as they are; {@link #problems} is checked by {@link #client}. Plain ldap:// works here
     * (for the tests against an in-memory server); the TLS trust applies to ldaps:// with a truststore.
     *
     * @throws AdDirectoryException ({@link AdErrorCode#TLS}) when the truststore cannot be read
     */
    public static LdapContextSource contextSource(AdLdapProperties.Ldap settings) {
        LdapContextSource source = new LdapContextSource();
        source.setUrl(settings.getUrl().trim());
        source.setUserDn(settings.getBindDn());
        source.setPassword(settings.getBindPassword());
        source.setAnonymousReadOnly(false);
        source.setPooled(false);
        // AD returns referrals to other partitions of the forest; they are not followed
        source.setReferral("ignore");

        Map<String, Object> environment = new HashMap<>();
        environment.put(BINARY_ATTRIBUTES, "objectGUID");
        environment.put(CONNECT_TIMEOUT, String.valueOf(settings.getConnectTimeout().toMillis()));
        environment.put(READ_TIMEOUT, String.valueOf(settings.getReadTimeout().toMillis()));
        if (!blank(settings.getTruststorePath())
                && settings.getUrl().trim().toLowerCase(Locale.ROOT).startsWith("ldaps://")) {
            AdLdapSocketFactory.use(sslContext(settings.getTruststorePath().trim(), settings.getTruststorePassword()));
            environment.put(SOCKET_FACTORY, AdLdapSocketFactory.class.getName());
        }
        source.setBaseEnvironmentProperties(environment);
        source.afterPropertiesSet();
        return source;
    }

    /** TLS trusting only the certificates of the truststore (JKS or PKCS12) */
    static SSLContext sslContext(String truststorePath, String truststorePassword) {
        File file = new File(truststorePath);
        try {
            char[] password = truststorePassword == null || truststorePassword.isEmpty()
                    ? null : truststorePassword.toCharArray();
            KeyStore truststore = KeyStore.getInstance(file, password);
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(truststore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trust.getTrustManagers(), null);
            log.info("AD sync: LDAP connection trusts the {} certificate(s) of {}", truststore.size(), file);
            return context;
        } catch (Exception e) {
            throw new AdDirectoryException(AdErrorCode.TLS, "AD_LDAP_TRUSTSTORE_PATH " + file + " cannot be read ("
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + "): check the path inside the container"
                    + " and AD_LDAP_TRUSTSTORE_PASSWORD, see spec section 8.1", e);
        }
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
