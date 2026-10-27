package cs_orgchart.ad.client;

import cs_orgchart.ad.model.AdEmployee;

import java.util.List;
import java.util.Optional;

/**
 * Reads employees from Active Directory (AD_SYNC_MODE=ldap) or from the mock fixture (AD_SYNC_MODE=mock).
 * Every failure is an {@link AdDirectoryException} with its {@link AdErrorCode}.
 */
public interface AdDirectoryClient {

    /** All entries of the configured filter (spec §4.2): the Central Services employees */
    List<AdEmployee> fetchAll();

    /** Any user by distinguished name, in any department (managers outside Central Services) */
    Optional<AdEmployee> findByDn(String dn);

    /** Any user by userPrincipalName, in any department (performance managers outside Central Services) */
    Optional<AdEmployee> findByUpn(String upn);
}
