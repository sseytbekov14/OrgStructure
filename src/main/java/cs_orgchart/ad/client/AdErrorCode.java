package cs_orgchart.ad.client;

/**
 * Why an AD read failed; stored as {@code ad_sync_log.error_code} (spec v2.1 §6.2, §7).
 */
public enum AdErrorCode {
    /** Invalid credentials (LDAP error 49): scheduled and startup syncs are suspended (AuthCircuitBreaker) */
    AUTH,
    /** Certificate of the domain controller not trusted, or TLS handshake failed */
    TLS,
    /** Domain controller unreachable: DNS, refused, connect timeout (port 636 closed?) */
    NETWORK,
    /** Connected, but AD did not answer in time (read timeout, time limit) */
    TIMEOUT,
    /** The bind account may not read the entries (LDAP error 50) */
    PERMISSION,
    /** Search size limit exceeded (paging not working) */
    LIMIT,
    /** AD_* settings missing or not allowed */
    CONFIG,
    /** Too few entries fetched: the database is left unchanged (sync, spec §7 step 5) */
    SAFETY,
    /** Any other LDAP error */
    LDAP
}
