package cs_orgchart.ad.mapper;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A department after {@link DepartmentNormalizer} (spec v3 §5.2): up to 4 levels (null when absent) and the
 * segments the dictionaries do not know.
 */
public record NormalizedDepartment(String level1, String level2, String level3, String level4,
                                   List<String> unmappedSegments) {

    public static final NormalizedDepartment EMPTY = new NormalizedDepartment(null, null, null, null, List.of());

    public NormalizedDepartment {
        unmappedSegments = List.copyOf(unmappedSegments);
    }

    public List<String> levels() {
        return Stream.of(level1, level2, level3, level4).filter(Objects::nonNull).toList();
    }

    /** "Central Services / ITS / ITS General" */
    public String path() {
        return levels().stream().collect(Collectors.joining(" / "));
    }
}
