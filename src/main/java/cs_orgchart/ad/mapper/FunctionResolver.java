package cs_orgchart.ad.mapper;

import cs_orgchart.department.DepartmentDictionaries;
import cs_orgchart.department.DepartmentDictionaries.FunctionMapping;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Normalized department → function shown on the site, {@code cs} (spec v2.1 §5.5): the department_function_map row
 * for (level 2, level 3) first, then the row for (level 2, any level 3); otherwise level 2 itself, reported once per
 * sync run as "Unmapped function". Also the default group of a person without an overlay group.
 */
@Slf4j
public class FunctionResolver {

    private final DepartmentDictionaries dictionaries;

    public FunctionResolver(DepartmentDictionaries dictionaries) {
        this.dictionaries = dictionaries;
    }

    /**
     * @param csName  function name on the site; null when the department has no level 2
     * @param visible whether the function is shown
     * @param mapped  false: no department_function_map row, {@code csName} is level 2 itself
     */
    public record Resolution(String csName, boolean visible, boolean mapped) {
    }

    /** One department, without logging */
    public Resolution resolve(String deptLevel2, String deptLevel3) {
        return new Lookup(dictionaries).resolve(deptLevel2, deptLevel3);
    }

    public Resolution resolve(NormalizedDepartment department) {
        return resolve(department.level2(), department.level3());
    }

    /**
     * The group of a person without an overlay group: the last non-general level, i.e. level 4 if present,
     * otherwise level 3 unless it is a general node (then no group: null).
     */
    public String defaultGroup(NormalizedDepartment department) {
        return new Lookup(dictionaries).defaultGroup(department);
    }

    /** A sync run: the dictionaries as they are now, each unmapped function logged once */
    public Run newRun() {
        return new Run(new Lookup(dictionaries));
    }

    public static final class Run {

        private final Lookup lookup;
        private final Map<String, String> unmapped = new LinkedHashMap<>();

        private Run(Lookup lookup) {
            this.lookup = lookup;
        }

        public Resolution resolve(NormalizedDepartment department) {
            Resolution resolution = lookup.resolve(department.level2(), department.level3());
            if (!resolution.mapped() && resolution.csName() != null
                    && unmapped.putIfAbsent(resolution.csName().toLowerCase(Locale.ROOT), resolution.csName()) == null) {
                log.warn("Unmapped function: {}", resolution.csName());
            }
            return resolution;
        }

        public String defaultGroup(NormalizedDepartment department) {
            return lookup.defaultGroup(department);
        }

        public List<String> unmappedFunctions() {
            return List.copyOf(unmapped.values());
        }
    }

    private static final class Lookup {

        /** "level2\u0000level3" (lower case, level 3 empty for "any") → row */
        private final Map<String, FunctionMapping> rows = new HashMap<>();
        private final Set<String> generalNodes = new HashSet<>();

        Lookup(DepartmentDictionaries dictionaries) {
            for (FunctionMapping row : dictionaries.functionMappings()) {
                rows.putIfAbsent(key(row.deptLevel2(), row.deptLevel3()), row);
            }
            dictionaries.generalNodes().values().forEach(general -> generalNodes.add(lower(general)));
        }

        Resolution resolve(String level2, String level3) {
            if (level2 == null || level2.isBlank()) {
                return new Resolution(null, false, false);
            }
            FunctionMapping row = level3 == null ? null : rows.get(key(level2, level3));
            if (row == null) {
                row = rows.get(key(level2, null));
            }
            return row == null
                    ? new Resolution(level2.trim(), true, false)
                    : new Resolution(row.csName(), row.visible(), true);
        }

        String defaultGroup(NormalizedDepartment department) {
            if (department.level4() != null) {
                return department.level4();
            }
            return department.level3() == null || generalNodes.contains(lower(department.level3()))
                    ? null : department.level3();
        }

        private static String key(String level2, String level3) {
            return lower(level2) + '\u0000' + (level3 == null ? "" : lower(level3));
        }

        private static String lower(String value) {
            return value.trim().toLowerCase(Locale.ROOT);
        }
    }
}
