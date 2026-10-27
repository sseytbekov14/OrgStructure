package cs_orgchart.ad.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry as the directory returned it, not yet validated: LDAP attribute name → first value (String, or byte[] for
 * objectGUID; the mock fixture has objectGUID as a UUID string). {@link cs_orgchart.ad.mapper.AdEmployeeMapper}
 * turns it into an {@link AdEmployee} or skips it (spec v3 §4.4).
 */
public record AdRecord(Map<String, Object> attributes) {

    public AdRecord {
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /** The value of an attribute, any case (LDAP attribute names are case-insensitive); null when absent */
    public Object get(String name) {
        Object value = attributes.get(name);
        if (value != null) {
            return value;
        }
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
