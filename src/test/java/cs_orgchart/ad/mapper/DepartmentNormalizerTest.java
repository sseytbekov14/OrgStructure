package cs_orgchart.ad.mapper;

import cs_orgchart.department.DepartmentDictionaries;
import cs_orgchart.department.SpecDepartmentDictionaries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The department normalizer (spec v3 §5.2) on every test case of §5.4.
 */
class DepartmentNormalizerTest {

    private final DepartmentNormalizer normalizer = new DepartmentNormalizer(SpecDepartmentDictionaries.INSTANCE);

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            Central services/ITS/ITS General/IT Operations             | Central Services / ITS / ITS General / IT Operations
            CENTRAL SERVICES/ITS/ITS/ITS General                       | Central Services / ITS / ITS General
            CS/ITS/ITS/ITS General                                     | Central Services / ITS / ITS General
            CS / ITS                                                   | Central Services / ITS / ITS General
            Central services/ITS/IT PMO/IT PMO                         | Central Services / ITS / IT PMO
            CS/ITS/ITS/IT PMO                                          | Central Services / ITS / IT PMO
            Central services/QRM/QRM/QRM                               | Central Services / QRM / QRM General
            CS/QRM/QRM/QRM General                                     | Central Services / QRM / QRM General
            CS / QRM                                                   | Central Services / QRM / QRM General
            CS / OGC                                                   | Central Services / Office of General Counsel / OGC General
            CS / Office of General Counsel                             | Central Services / Office of General Counsel / OGC General
            CS/Office General Counsel/OGC/OGC General                  | Central Services / Office of General Counsel / OGC General
            Central services/OGC/OGC/OGC General                       | Central Services / Office of General Counsel / OGC General
            CS / F&O                                                   | Central Services / Facilities & Operations / F&O General
            Central services/Facilities & Operations                   | Central Services / Facilities & Operations / F&O General
            Central services/F&O/F&O/F&O General                       | Central Services / Facilities & Operations / F&O General
            CS/F&O/Facilities & Operations/Proposals & Tenders         | Central Services / Facilities & Operations / Proposals & Tenders
            CS/Facilities & Operations/F&O/Proposals & Tenders         | Central Services / Facilities & Operations / Proposals & Tenders
            Central services/Facilities & Operations/F&O/Tenders       | Central Services / Facilities & Operations / Proposals & Tenders
            CS/People/People/HR Admin                                  | Central Services / People / HR Administration
            Central services/People/People/HR Administration           | Central Services / People / HR Administration
            CS / People                                                | Central Services / People / People General
            Central services/People/L&D/L&D                            | Central Services / People / L&D
            CS / People / Talent Acquisition / Graduate                | Central Services / People / Talent Acquisition / Graduate Recruiting
            CS/People/Talent Acquisition/Graduate Recruiting           | Central Services / People / Talent Acquisition / Graduate Recruiting
            CENTRAL SERVICES/People/Compensation & Benefits            | Central Services / People / Compensation & Benefits
            CS / Administration Office                                 | Central Services / Executives / Administration Office
            CS/Executives/Executives/Administration Office             | Central Services / Executives / Administration Office
            Central services/Executives/Administration Office/         | Central Services / Executives / Administration Office
            Central services/Finance/Finance General                   | Central Services / Finance / Finance General
            Central services/Finance/Finance General/Finance General   | Central Services / Finance / Finance General
            Central services/Finance/Finance General/Accounting General | Central Services / Finance / Finance General / Accounting General
            CENTRAL SERVICES/Finance/Finance/Finance General           | Central Services / Finance / Finance General
            Central services/Markets/Markets General/Brand & Hospitality | Central Services / Markets / Markets General / Brand & Hospitality
            CS/Markets/Markets/Designers Markets                       | Central Services / Markets / Designers Markets
            CS/Procurement/Procurement General/Procurement General     | Central Services / Procurement / Procurement General
            """)
    void specTestCase(String department, String expected) {
        NormalizedDepartment result = normalizer.normalize(department);

        assertThat(result.path()).isEqualTo(expected);
        assertThat(result.unmappedSegments()).isEmpty();
    }

    @Test
    void levelsAreSetInOrderAndTheRestIsNull() {
        NormalizedDepartment result = normalizer.normalize("CS / ITS");

        assertThat(result.level1()).isEqualTo("Central Services");
        assertThat(result.level2()).isEqualTo("ITS");
        assertThat(result.level3()).isEqualTo("ITS General");
        assertThat(result.level4()).isNull();
    }

    @Test
    void emptyDepartmentHasNoLevels() {
        assertThat(normalizer.normalize(null)).isEqualTo(NormalizedDepartment.EMPTY);
        assertThat(normalizer.normalize("  / ")).isEqualTo(NormalizedDepartment.EMPTY);
    }

    @Test
    void unknownSegmentIsKeptAsItIsAndReported() {
        NormalizedDepartment result = normalizer.normalize("CS/ITS/Cloud Platform");

        assertThat(result.path()).isEqualTo("Central Services / ITS / Cloud Platform");
        assertThat(result.unmappedSegments()).containsExactly("Cloud Platform");
    }

    @Test
    void functionAndGeneralNodeNamesTakeTheDictionarySpelling() {
        assertThat(normalizer.normalize("cs/its/its general").path()).isEqualTo("Central Services / ITS / ITS General");
    }

    @Test
    void moreThanFourLevelsAreJoinedIntoLevelFour() {
        NormalizedDepartment result = normalizer.normalize("CS/ITS/ITS General/IT Operations/Network/Wi-Fi");

        assertThat(result.level4()).isEqualTo("IT Operations / Network / Wi-Fi");
        assertThat(result.unmappedSegments()).containsExactly("Network", "Wi-Fi");
    }

    @Test
    void runReportsEachUnmappedSegmentOnce() {
        DepartmentNormalizer.Run run = normalizer.newRun();
        run.normalize("CS/ITS/Cloud Platform");
        run.normalize("Central services/ITS/cloud platform");
        run.normalize("CS/Finance/Treasury");
        run.normalize("CS/ITS/ITS/ITS General");

        assertThat(run.unmappedSegments()).containsExactly("Cloud Platform", "Treasury");
        assertThat(run.unmappedCount()).isEqualTo(2);
    }

    @Test
    void runUsesTheDictionariesAsTheyAreWhenItStarts() {
        MutableDictionaries dictionaries = new MutableDictionaries();
        DepartmentNormalizer withNewAlias = new DepartmentNormalizer(dictionaries);
        assertThat(withNewAlias.newRun().normalize("CS/ITS/Cloud").unmappedSegments()).containsExactly("Cloud");

        dictionaries.aliases = Map.of("cloud", "Cloud Platform");

        assertThat(withNewAlias.newRun().normalize("CS/ITS/Cloud").path())
                .isEqualTo("Central Services / ITS / Cloud Platform");
    }

    /** The spec dictionaries plus aliases that a test changes */
    private static final class MutableDictionaries implements DepartmentDictionaries {
        Map<String, String> aliases = Map.of();

        @Override
        public Map<String, String> aliases() {
            Map<String, String> all = new HashMap<>(SpecDepartmentDictionaries.INSTANCE.aliases());
            all.putAll(aliases);
            return all;
        }

        @Override
        public Map<String, String> generalNodes() {
            return SpecDepartmentDictionaries.INSTANCE.generalNodes();
        }

        @Override
        public List<FunctionMapping> functionMappings() {
            return SpecDepartmentDictionaries.INSTANCE.functionMappings();
        }
    }
}
