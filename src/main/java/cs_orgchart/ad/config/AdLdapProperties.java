package cs_orgchart.ad.config;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The AD_* settings of the AD sync (spec v2.1 §4.1, §8), mapped in application.yaml. Read only when AD_SYNC_MODE is
 * set ({@link AdConfig}).
 */
@Data
@ConfigurationProperties("ad")
public class AdLdapProperties {

    /** Spec §4.2: Central Services users, enabled accounts only */
    public static final String DEFAULT_FILTER = "(&(objectCategory=person)(objectClass=user)"
            + "(|(department=Central Services*)(department=CS/*)(department=CS /*)(department=CS))"
            + "(!(userAccountControl:1.2.840.113556.1.4.803:=2)))";

    private final Sync sync = new Sync();
    private final Ldap ldap = new Ldap();

    @Data
    public static class Sync {
        /** AD_SYNC_ENABLED */
        private boolean enabled = false;
        /** AD_SYNC_MODE: mock | ldap; empty: no AD components at all */
        private String mode = "";
        /** AD_SYNC_CRON; "-" disables the scheduler */
        private String cron = "0 */30 * * * *";
        /** AD_SYNC_ON_STARTUP */
        private boolean onStartup = false;
        /** AD_SYNC_MIN_RATIO */
        private double minRatio = 0.5;
    }

    @Data
    public static class Ldap {
        /** AD_LDAP_URL, ldaps:// only */
        private String url = "";
        /** AD_LDAP_BASE_DN */
        private String baseDn = "";
        /** AD_LDAP_BIND_DN: UPN form (user@domain) or the full DN */
        private String bindDn = "";
        /** AD_LDAP_BIND_PASSWORD */
        @ToString.Exclude
        private String bindPassword = "";
        /** AD_LDAP_FILTER; empty: {@link #DEFAULT_FILTER} */
        private String filter = "";
        /** AD_LDAP_PAGE_SIZE (AD returns at most 1000 per page) */
        private int pageSize = 500;
        /** AD_LDAP_TRUSTSTORE_PATH: CA certificates trusted for the LDAP connection only (spec §8.1) */
        private String truststorePath = "";
        /** AD_LDAP_TRUSTSTORE_PASSWORD */
        @ToString.Exclude
        private String truststorePassword = "";
        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(60);

        public String effectiveFilter() {
            return filter == null || filter.isBlank() ? DEFAULT_FILTER : filter.trim();
        }
    }
}
