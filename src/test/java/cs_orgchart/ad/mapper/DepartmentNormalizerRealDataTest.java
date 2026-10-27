package cs_orgchart.ad.mapper;

import cs_orgchart.department.SpecDepartmentDictionaries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The normalizer on every real AD department value (src/test/resources/departments.csv, spec §5.4 real-data test):
 * no exception, level 1 and 2 always set, nothing unmapped, and exactly the 20 results of Appendix A.
 */
class DepartmentNormalizerRealDataTest {

    private final DepartmentNormalizer normalizer = new DepartmentNormalizer(SpecDepartmentDictionaries.INSTANCE);

    static List<DepartmentsCsv.Row> rows() {
        return DepartmentsCsv.rows();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    void everyRealValueNormalizes(DepartmentsCsv.Row row) {
        assertThatCode(() -> normalizer.normalize(row.department())).doesNotThrowAnyException();
        NormalizedDepartment result = normalizer.normalize(row.department());

        assertThat(result.level1()).isEqualTo("Central Services");
        assertThat(result.level2()).isNotBlank();
        assertThat(result.unmappedSegments()).isEmpty();
    }

    @Test
    void theRealValuesGiveExactlyTheTwentyGoldenResults() {
        DepartmentNormalizer.Run run = normalizer.newRun();
        Set<String> results = rows().stream()
                .map(row -> run.normalize(row.department()).path())
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(rows()).hasSize(51);
        assertThat(results).hasSize(20).containsExactlyInAnyOrderElementsOf(DepartmentsCsv.golden());
        assertThat(run.unmappedCount()).isZero();
    }
}
