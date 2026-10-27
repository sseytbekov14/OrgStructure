package cs_orgchart.ad.client;

import cs_orgchart.ad.model.AdRecord;

import java.util.List;
import java.util.Optional;

/**
 * Reads employees from Active Directory (AD_SYNC_MODE=ldap) or from the mock fixture (AD_SYNC_MODE=mock).
 * The entries come as they are: {@link cs_orgchart.ad.mapper.AdEmployeeMapper} validates them (spec v3 §4.4).
 * Every failure to read is an {@link AdDirectoryException} with its {@link AdErrorCode}.
 */
public interface AdDirectoryClient {

    /** All entries of the configured filter (spec §4.2): the Central Services employees */
    List<AdRecord> fetchAll();

    /** Any user by distinguished name, in any department (managers outside Central Services) */
    Optional<AdRecord> findByDn(String dn);

    /** Any user by userPrincipalName, in any department (performance managers outside Central Services) */
    Optional<AdRecord> findByUpn(String upn);
}
