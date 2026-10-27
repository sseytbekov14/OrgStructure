package cs_orgchart.ad.mapper;

import cs_orgchart.department.SpecDepartmentDictionaries;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Normalized department → site function and default group (spec v2.1 §5.5).
 */
class FunctionResolverTest {

    private final DepartmentNormalizer normalizer = new DepartmentNormalizer(SpecDepartmentDictionaries.INSTANCE);
    private final FunctionResolver resolver = new FunctionResolver(SpecDepartmentDictionaries.INSTANCE);

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            CS/F&O/Facilities & Operations/Proposals & Tenders     | Tenders
            Central services/Facilities & Operations/F&O/Tenders   | Tenders
            CS / F&O                                               | F&O
            Central services/F&O/F&O/F&O General                   | F&O
            CS / Administration Office                             | F&O
            CS/Executives/Executives/Administration Office         | F&O
            CS/Procurement/Procurement General/Procurement General | F&O
            CS / OGC                                               | OGC
            CS/Office General Counsel/OGC/OGC General              | OGC
            CS / People / Talent Acquisition / Graduate            | People
            Central services/Finance/Finance General/Accounting General | Finance
            CS/ITS/ITS/IT PMO                                      | ITS
            CS/Markets/Markets/Designers Markets                   | Markets
            CS / QRM                                               | QRM
            """)
    void functionOfADepartment(String department, String cs) {
        FunctionResolver.Resolution resolution = resolver.resolve(normalizer.normalize(department));

        assertThat(resolution.csName()).isEqualTo(cs);
        assertThat(resolution.mapped()).isTrue();
        assertThat(resolution.visible()).isTrue();
    }

    @Test
    void exactLevelThreeRowWinsOverTheAnyRow() {
        assertThat(resolver.resolve("Facilities & Operations", "Proposals & Tenders").csName()).isEqualTo("Tenders");
        assertThat(resolver.resolve("facilities & operations", "PROPOSALS & TENDERS").csName()).isEqualTo("Tenders");
        assertThat(resolver.resolve("Facilities & Operations", "F&O General").csName()).isEqualTo("F&O");
        assertThat(resolver.resolve("Facilities & Operations", null).csName()).isEqualTo("F&O");
    }

    @Test
    void unmappedLevelTwoIsTheFunctionItselfAndReportedOncePerRun() {
        FunctionResolver.Run run = resolver.newRun();
        FunctionResolver.Resolution first = run.resolve(normalizer.normalize("CS/Sustainability/ESG"));
        run.resolve(normalizer.normalize("CS/sustainability/Reporting"));

        assertThat(first.csName()).isEqualTo("Sustainability");
        assertThat(first.mapped()).isFalse();
        assertThat(first.visible()).isTrue();
        assertThat(run.unmappedFunctions()).containsExactly("Sustainability");
    }

    @Test
    void departmentWithoutLevelTwoHasNoFunction() {
        assertThat(resolver.resolve(normalizer.normalize("CS")).csName()).isNull();
    }

    @Test
    void theRealDepartmentsSpreadOverTheEightSiteFunctions() {
        Map<String, Integer> headcount = new TreeMap<>();
        for (DepartmentsCsv.Row row : DepartmentsCsv.rows()) {
            FunctionResolver.Resolution resolution = resolver.resolve(normalizer.normalize(row.department()));
            assertThat(resolution.mapped()).as(row.department()).isTrue();
            headcount.merge(resolution.csName(), row.count(), Integer::sum);
        }

        // The functions of spec §2.4 (the counts there are those of the export and change with every refresh)
        assertThat(headcount).containsOnlyKeys("People", "Finance", "ITS", "Markets", "QRM", "OGC", "F&O", "Tenders");
        assertThat(headcount.values()).allSatisfy(count -> assertThat(count).isPositive());
    }

    @ParameterizedTest(name = "{0} -> [{1}]")
    @CsvSource(delimiter = '|', nullValues = "-", textBlock = """
            Central services/ITS/ITS General/IT Operations              | IT Operations
            Central services/Finance/Finance General/Accounting General | Accounting General
            CS/ITS/ITS/IT PMO                                           | IT PMO
            CS/F&O/Facilities & Operations/Proposals & Tenders          | Proposals & Tenders
            CS / People / Talent Acquisition / Graduate                 | Graduate Recruiting
            CS/People/People/Talent Acquisition                         | Talent Acquisition
            CS / ITS                                                    | -
            Central services/Finance/Finance General                    | -
            CS / Administration Office                                  | -
            CS/Procurement/Procurement General/Procurement General      | -
            """)
    void defaultGroupIsTheLastNonGeneralLevel(String department, String group) {
        NormalizedDepartment normalized = normalizer.normalize(department);

        assertThat(resolver.defaultGroup(normalized)).isEqualTo(group);
        assertThat(resolver.newRun().defaultGroup(normalized)).isEqualTo(group);
    }
}
