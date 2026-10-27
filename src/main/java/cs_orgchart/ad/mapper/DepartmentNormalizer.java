package cs_orgchart.ad.mapper;

import cs_orgchart.department.DepartmentDictionaries;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Raw AD {@code department} of a Central Services employee → {@code dept_level1..4} (spec v2.1 §5.2):
 * <ol>
 *   <li>split by "/", trim, drop empty parts;</li>
 *   <li>replace each part by its alias (any case); a function or general node name takes the dictionary spelling;
 *       other parts stay as they are;</li>
 *   <li>level 1 is always Central Services;</li>
 *   <li>a level 2 "Administration Office" gets "Executives" inserted before it;</li>
 *   <li>parts equal to the function (level 2) and consecutive duplicates are removed;</li>
 *   <li>a department that is only the function gets its general node;</li>
 *   <li>more than 4 levels: the rest is joined into level 4 with " / " (logged).</li>
 * </ol>
 * A segment is known when it is an alias, a canonical name, a function name or a general node name (any case); the
 * others are reported as unmapped. {@link #newRun()} logs each unmapped segment once per sync run and counts them.
 */
@Slf4j
public class DepartmentNormalizer {

    public static final String CENTRAL_SERVICES = "Central Services";
    static final String ADMINISTRATION_OFFICE = "Administration Office";
    static final String EXECUTIVES = "Executives";
    static final int MAX_LEVELS = 4;

    private final DepartmentDictionaries dictionaries;

    public DepartmentNormalizer(DepartmentDictionaries dictionaries) {
        this.dictionaries = dictionaries;
    }

    /** One department, without logging the unmapped segments */
    public NormalizedDepartment normalize(String department) {
        return new Lookup(dictionaries).normalize(department);
    }

    /** A sync run: the dictionaries as they are now, each unmapped segment logged once */
    public Run newRun() {
        return new Run(new Lookup(dictionaries));
    }

    public static final class Run {

        private final Lookup lookup;
        /** lower case → as first seen */
        private final Map<String, String> unmapped = new LinkedHashMap<>();

        private Run(Lookup lookup) {
            this.lookup = lookup;
        }

        public NormalizedDepartment normalize(String department) {
            NormalizedDepartment result = lookup.normalize(department);
            for (String segment : result.unmappedSegments()) {
                if (unmapped.putIfAbsent(segment.toLowerCase(Locale.ROOT), segment) == null) {
                    log.warn("Unmapped department segment: {}", segment);
                }
            }
            return result;
        }

        /** The distinct unmapped segments of this run (unmapped_count) */
        public List<String> unmappedSegments() {
            return List.copyOf(unmapped.values());
        }

        public int unmappedCount() {
            return unmapped.size();
        }
    }

    private static final class Lookup {

        private final Map<String, String> aliases = new HashMap<>();
        /** lower case → dictionary spelling of the canonical, function and general node names */
        private final Map<String, String> names = new HashMap<>();
        /** lower-case function name → general node */
        private final Map<String, String> generalNodes = new HashMap<>();

        Lookup(DepartmentDictionaries dictionaries) {
            dictionaries.aliases().forEach((alias, canonical) -> {
                aliases.put(lower(alias), canonical);
                names.putIfAbsent(lower(canonical), canonical);
            });
            dictionaries.generalNodes().forEach((function, general) -> {
                generalNodes.put(lower(function), general);
                names.putIfAbsent(lower(function), function);
                names.putIfAbsent(lower(general), general);
            });
            dictionaries.functionMappings().forEach(m -> names.putIfAbsent(lower(m.deptLevel2()), m.deptLevel2()));
        }

        NormalizedDepartment normalize(String department) {
            if (department == null || department.isBlank()) {
                return NormalizedDepartment.EMPTY;
            }
            List<String> unmapped = new ArrayList<>();
            List<String> parts = new ArrayList<>();
            for (String raw : department.split("/")) {
                String part = raw.trim();
                if (part.isEmpty()) {
                    continue;
                }
                String key = lower(part);
                String alias = aliases.get(key);
                String name = alias != null ? alias : names.get(key);
                if (name == null) {
                    unmapped.add(part);
                }
                parts.add(name != null ? name : part);
            }
            if (parts.isEmpty()) {
                return NormalizedDepartment.EMPTY;
            }

            if (!parts.isEmpty() && parts.get(0).equalsIgnoreCase(CENTRAL_SERVICES)) {
                parts.remove(0);
            }
            if (!parts.isEmpty() && parts.get(0).equalsIgnoreCase(ADMINISTRATION_OFFICE)) {
                parts.add(0, EXECUTIVES);
            }

            List<String> levels = new ArrayList<>();
            levels.add(CENTRAL_SERVICES);
            if (!parts.isEmpty()) {
                String function = parts.get(0);
                levels.add(function);
                for (String part : parts.subList(1, parts.size())) {
                    if (!part.equalsIgnoreCase(function) && !part.equalsIgnoreCase(levels.get(levels.size() - 1))) {
                        levels.add(part);
                    }
                }
                if (levels.size() == 2) {
                    String general = generalNodes.get(lower(function));
                    if (general != null) {
                        levels.add(general);
                    }
                }
            }
            if (levels.size() > MAX_LEVELS) {
                String rest = String.join(" / ", levels.subList(MAX_LEVELS - 1, levels.size()));
                log.warn("Department has more than {} levels, the rest is joined into level {}: {}", MAX_LEVELS,
                        MAX_LEVELS, department);
                levels = new ArrayList<>(levels.subList(0, MAX_LEVELS - 1));
                levels.add(rest);
            }
            while (levels.size() < MAX_LEVELS) {
                levels.add(null);
            }
            return new NormalizedDepartment(levels.get(0), levels.get(1), levels.get(2), levels.get(3),
                    Collections.unmodifiableList(unmapped));
        }

        private static String lower(String value) {
            return value.trim().toLowerCase(Locale.ROOT);
        }
    }
}
