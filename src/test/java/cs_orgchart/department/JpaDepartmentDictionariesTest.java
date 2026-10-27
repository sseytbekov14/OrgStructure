package cs_orgchart.department;

import cs_orgchart.ad.mapper.DepartmentNormalizer;
import cs_orgchart.ad.mapper.DepartmentsCsv;
import cs_orgchart.ad.mapper.FunctionResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dictionaries as the application reads them from PostgreSQL (seeded by migration V4) are those of the spec,
 * i.e. the ones the unit tests use ({@link SpecDepartmentDictionaries}), and a reload sees changes of the tables.
 */
@SpringBootTest
@ActiveProfiles("local")
class JpaDepartmentDictionariesTest {

    @Autowired
    private DepartmentAliasRepository aliases;

    @Autowired
    private DepartmentGeneralNodeRepository generalNodes;

    @Autowired
    private DepartmentFunctionMapRepository functionMap;

    private JpaDepartmentDictionaries fromDatabase() {
        return new JpaDepartmentDictionaries(aliases, generalNodes, functionMap);
    }

    @Test
    void databaseHoldsTheDictionariesOfTheSpec() {
        JpaDepartmentDictionaries db = fromDatabase();
        SpecDepartmentDictionaries spec = SpecDepartmentDictionaries.INSTANCE;

        assertThat(db.aliases()).isEqualTo(spec.aliases());
        assertThat(db.generalNodes()).isEqualTo(spec.generalNodes());
        assertThat(db.functionMappings()).containsExactlyInAnyOrderElementsOf(spec.functionMappings());
    }

    @Test
    void realDepartmentsNormalizeToTheGoldenResultsWithTheDatabaseDictionaries() {
        JpaDepartmentDictionaries db = fromDatabase();
        DepartmentNormalizer.Run run = new DepartmentNormalizer(db).newRun();
        FunctionResolver resolver = new FunctionResolver(db);

        Set<String> results = DepartmentsCsv.rows().stream()
                .map(row -> run.normalize(row.department()))
                .peek(d -> assertThat(resolver.resolve(d).mapped()).as(d.path()).isTrue())
                .map(d -> d.path())
                .collect(Collectors.toSet());

        assertThat(results).containsExactlyInAnyOrderElementsOf(DepartmentsCsv.golden());
        assertThat(run.unmappedCount()).isZero();
    }

    @Test
    @Transactional
    void reloadSeesANewSpelling() {
        JpaDepartmentDictionaries db = fromDatabase();
        DepartmentNormalizer normalizer = new DepartmentNormalizer(db);
        assertThat(normalizer.normalize("CS/ITS/Cloud").unmappedSegments()).containsExactly("Cloud");

        aliases.save(DepartmentAlias.builder().alias("cloud").canonical("Cloud Platform").build());
        assertThat(db.aliases()).as("cached until reload").doesNotContainKey("cloud");
        db.reload();

        assertThat(normalizer.normalize("CS/ITS/Cloud").path()).isEqualTo("Central Services / ITS / Cloud Platform");
    }
}
