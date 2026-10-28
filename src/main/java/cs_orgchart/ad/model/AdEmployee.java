package cs_orgchart.ad.model;

import java.time.Instant;
import java.util.UUID;

/**
 * One user entry as read from Active Directory (spec v3 §4.3): values trimmed, empty ones null,
 * {@code enabled} from userAccountControl and {@code countryCode} from the DN. The department is the raw AD value.
 * U6: {@code countryName} from co (at most 128 characters), {@code countryIso} from c (two letters, upper case).
 */
public record AdEmployee(
        UUID adGuid,
        String dn,
        String login,
        String upn,
        String email,
        String displayName,
        String firstName,
        String lastName,
        String jobTitle,
        String department,
        String company,
        String managerDn,
        String pmUpn,
        String office,
        String city,
        String phone,
        String mobile,
        String employeeId,
        boolean enabled,
        Instant adWhenChanged,
        String countryCode,
        String countryName,
        String countryIso) {
}
