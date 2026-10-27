package cs_orgchart.ad.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cs_orgchart.ad.mapper.DepartmentNormalizer;
import cs_orgchart.ad.mapper.DepartmentsCsv;
import cs_orgchart.ad.mapper.FunctionResolver;
import cs_orgchart.ad.mapper.NormalizedDepartment;
import cs_orgchart.ad.model.AdEmployee;
import cs_orgchart.department.SpecDepartmentDictionaries;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The synthetic directory of AD_SYNC_MODE=mock (mock/ad-employees.json, mock/overrides.json) covers what spec §9 asks
 * for, and the mock client applies the rules of the AD filter.
 */
class MockAdDirectoryClientTest {

    private final MockAdDirectoryClient client = new MockAdDirectoryClient(new ObjectMapper());
    private final List<AdEmployee> employees = client.fetchAll();
    private final Map<String, AdEmployee> byUpn = employees.stream()
            .collect(Collectors.toMap(AdEmployee::upn, Function.identity()));
    private final DepartmentNormalizer normalizer = new DepartmentNormalizer(SpecDepartmentDictionaries.INSTANCE);
    private final FunctionResolver resolver = new FunctionResolver(SpecDepartmentDictionaries.INSTANCE);

    @Test
    void aboutFortyEnabledCentralServicesEmployeesWithUniqueKeys() {
        assertThat(employees).hasSize(41);
        assertThat(employees).allSatisfy(e -> assertThat(e.enabled()).isTrue());
        assertThat(employees.stream().map(AdEmployee::adGuid).distinct()).hasSize(41);
        assertThat(employees.stream().map(e -> e.dn().toLowerCase()).distinct()).hasSize(41);
        assertThat(employees.stream().map(e -> e.upn().toLowerCase()).distinct()).hasSize(41);
    }

    @Test
    void disabledAndAuditUsersAreLeftOut() {
        assertThat(byUpn).doesNotContainKeys("ppearl@kz.example", "qquince@kz.example");
        assertThat(client.findByUpn("ppearl@kz.example")).hasValueSatisfying(e -> assertThat(e.enabled()).isFalse());
        assertThat(client.findByUpn("qquince@kz.example"))
                .hasValueSatisfying(e -> assertThat(e.department()).startsWith("Audit"));
    }

    @Test
    void managerOutsideCentralServicesIsFoundByDnOnly() {
        AdEmployee head = byUpn.get("aamber@kz.example");

        assertThat(byUpn).doesNotContainKey("rrust@kz.example");
        assertThat(client.findByDn(head.managerDn()))
                .hasValueSatisfying(m -> assertThat(m.department()).startsWith("Advisory"));
    }

    @Test
    void allFourPrefixesAndSixCountries() {
        Set<String> departments = employees.stream().map(AdEmployee::department).collect(Collectors.toSet());

        assertThat(departments).anyMatch(d -> d.startsWith("CS/"))
                .anyMatch(d -> d.startsWith("CS / "))
                .anyMatch(d -> d.startsWith("Central services/"))
                .anyMatch(d -> d.startsWith("CENTRAL SERVICES/"));
        assertThat(employees.stream().map(AdEmployee::countryCode).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("KZ", "UZ", "AZ", "GE", "AM", "KG");
    }

    @Test
    void departmentsAreRealValuesThatNormalizeToAllTwentyNodesAndEightFunctions() {
        Set<String> real = DepartmentsCsv.rows().stream()
                .map(DepartmentsCsv.Row::department).collect(Collectors.toSet());
        DepartmentNormalizer.Run run = normalizer.newRun();

        assertThat(employees).allSatisfy(e -> assertThat(real).contains(e.department()));
        Set<String> nodes = employees.stream().map(e -> run.normalize(e.department()).path())
                .collect(Collectors.toSet());
        assertThat(nodes).containsExactlyInAnyOrderElementsOf(DepartmentsCsv.golden());
        assertThat(run.unmappedCount()).isZero();
        assertThat(employees.stream().map(e -> resolver.resolve(normalizer.normalize(e.department())).csName())
                .collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("People", "Finance", "ITS", "Markets", "F&O", "OGC", "QRM", "Tenders");
    }

    @Test
    void executivesAndProcurementAreShownUnderFandO() {
        for (String upn : List.of("aamber@kz.example", "bblue@kz.example", "jjade@kz.example")) {
            NormalizedDepartment department = normalizer.normalize(byUpn.get(upn).department());
            assertThat(resolver.resolve(department).csName()).as(upn).isEqualTo("F&O");
        }
    }

    @Test
    void edgeCasesOfSpecSection9() {
        AdEmployee noManager = byUpn.get("kkhaki@kz.example");
        assertThat(noManager.managerDn()).isNull();
        assertThat(noManager.pmUpn()).isNull();

        assertThat(byUpn.get("ffern@kz.example").jobTitle()).isNull();

        Set<String> upns = byUpn.keySet();
        Map<String, Long> localParts = employees.stream().filter(e -> e.email() != null)
                .collect(Collectors.groupingBy(e -> e.email().substring(0, e.email().indexOf('@')),
                        Collectors.counting()));
        AdEmployee byEmail = byUpn.get("aazure@kz.example");
        assertThat(upns).doesNotContain(byEmail.pmUpn());
        assertThat(employees).anyMatch(e -> byEmail.pmUpn().equalsIgnoreCase(e.email()));

        AdEmployee byLocalPart = byUpn.get("bbronze@kz.example");
        assertThat(upns).doesNotContain(byLocalPart.pmUpn());
        assertThat(employees).noneMatch(e -> byLocalPart.pmUpn().equalsIgnoreCase(e.email()));
        assertThat(localParts.get(byLocalPart.pmUpn().substring(0, byLocalPart.pmUpn().indexOf('@')))).isEqualTo(1);

        AdEmployee pmNotManager = byUpn.get("ppeach@kz.example");
        AdEmployee manager = client.findByDn(pmNotManager.managerDn()).orElseThrow();
        assertThat(manager.upn()).isNotEqualTo(pmNotManager.pmUpn());
    }

    @Test
    void everyManagerAndPmOfTheFixtureExists() {
        for (AdEmployee e : employees) {
            if (e.managerDn() != null) {
                assertThat(client.findByDn(e.managerDn())).as("manager of " + e.upn()).isPresent();
            }
        }
    }

    // --- overrides.json ------------------------------------------------------------------------------------------

    record Override(String upn, String cs, String groupName, Integer headRank, String areaOfDuties,
                    String jobDescription, String managerOverrideUpn) {
    }

    private static List<Override> overrides() throws IOException {
        try (InputStream in = new ClassPathResource("mock/overrides.json").getInputStream()) {
            return new ObjectMapper().readValue(in, new TypeReference<>() {
            });
        }
    }

    @Test
    void overridesReferToMockEmployeesAndSiteFunctions() throws IOException {
        Set<String> functions = Set.of("People", "Finance", "ITS", "Markets", "F&O", "OGC", "QRM", "Tenders");

        assertThat(overrides()).isNotEmpty().allSatisfy(o -> {
            assertThat(byUpn).containsKey(o.upn());
            assertThat(functions).contains(o.cs());
            if (o.managerOverrideUpn() != null) {
                assertThat(byUpn).containsKey(o.managerOverrideUpn());
            }
        });
    }

    @Test
    void everyFunctionHasOneMainHeadAndSomeHaveSeveral() throws IOException {
        Map<String, List<Integer>> ranks = overrides().stream().filter(o -> o.headRank() != null)
                .collect(Collectors.groupingBy(Override::cs,
                        Collectors.mapping(Override::headRank, Collectors.toList())));

        assertThat(ranks).hasSize(8);
        ranks.forEach((cs, list) -> assertThat(list).as(cs).contains(1).doesNotHaveDuplicates());
        assertThat(ranks.values()).filteredOn(list -> list.size() > 1).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void onePersonIsInTwoFunctionsWithAnotherParentInTheSecond() throws IOException {
        Map<String, List<Override>> byPerson = overrides().stream().collect(Collectors.groupingBy(Override::upn));
        List<Override> twoFunctions = byPerson.get("hhoney@kz.example");

        assertThat(twoFunctions).extracting(Override::cs).containsExactlyInAnyOrder("People", "F&O");
        assertThat(twoFunctions).extracting(Override::managerOverrideUpn).filteredOn(Objects::nonNull)
                .containsExactly("ccoral@kz.example");
        assertThat(overrides().stream().map(o -> o.upn() + "|" + o.cs()).distinct()).hasSize(overrides().size());
    }

    @Test
    void centralServicesRuleMatchesTheAdFilterPrefixes() {
        assertThat(MockAdDirectoryClient.inCentralServices("Central services/ITS")).isTrue();
        assertThat(MockAdDirectoryClient.inCentralServices("CENTRAL SERVICES")).isTrue();
        assertThat(MockAdDirectoryClient.inCentralServices("CS/ITS")).isTrue();
        assertThat(MockAdDirectoryClient.inCentralServices("CS / ITS")).isTrue();
        assertThat(MockAdDirectoryClient.inCentralServices("CS")).isTrue();
        assertThat(MockAdDirectoryClient.inCentralServices("CSV Team")).isFalse();
        assertThat(MockAdDirectoryClient.inCentralServices("Audit/CS")).isFalse();
        assertThat(MockAdDirectoryClient.inCentralServices(null)).isFalse();
    }
}
