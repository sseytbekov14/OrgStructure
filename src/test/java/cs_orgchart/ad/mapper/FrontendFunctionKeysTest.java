package cs_orgchart.ad.mapper;

import cs_orgchart.department.DepartmentDictionaries.FunctionMapping;
import cs_orgchart.department.SpecDepartmentDictionaries;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The function names of department_function_map (spec §5.5) are the function keys of the frontend: an AD employee
 * whose cs is not one of them would get no icon, no FAQ and the wrong default view. JpaDepartmentDictionariesTest
 * checks that the database holds the same function map.
 */
class FrontendFunctionKeysTest {

    private static final Path JS = Path.of("src/main/resources/static/js");

    private static final Set<String> CS_NAMES = SpecDepartmentDictionaries.INSTANCE.functionMappings().stream()
            .map(FunctionMapping::csName)
            .collect(Collectors.toCollection(LinkedHashSet::new));

    @Test
    void everyFunctionHasAnIcon() throws IOException {
        assertThat(topLevelKeys(objectLiteral(read("main.js"), "FUNC_ICONS"))).containsExactlyInAnyOrderElementsOf(CS_NAMES);
    }

    @Test
    void everyFunctionHasAFaq() throws IOException {
        assertThat(topLevelKeys(objectLiteral(read("function.js"), "FUNCTION_FAQ")))
                .containsExactlyInAnyOrderElementsOf(CS_NAMES);
    }

    /** TREE_BY_DEFAULT lists only the functions that open as a tree (the others open as groups) */
    @Test
    void functionsOpenedAsATreeAreKnownFunctions() throws IOException {
        Matcher array = Pattern.compile("const TREE_BY_DEFAULT\\s*=\\s*\\[(.*?)];", Pattern.DOTALL)
                .matcher(read("function.js"));
        assertThat(array.find()).as("TREE_BY_DEFAULT in function.js").isTrue();
        Set<String> tree = Pattern.compile("['\"]([^'\"]+)['\"]").matcher(array.group(1)).results()
                .map(m -> m.group(1)).collect(Collectors.toSet());

        assertThat(tree).isNotEmpty();
        assertThat(CS_NAMES).containsAll(tree);
    }

    private static String read(String file) throws IOException {
        return Files.readString(JS.resolve(file), StandardCharsets.UTF_8);
    }

    /** The text between the braces of {@code const NAME = { ... };} */
    private static String objectLiteral(String js, String name) {
        int start = js.indexOf("const " + name);
        assertThat(start).as(name + " in the frontend").isNotNegative();
        int open = js.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < js.length(); i++) {
            char c = js.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return js.substring(open + 1, i);
            }
        }
        throw new AssertionError(name + " is not closed");
    }

    /** Keys at nesting depth 0 of an object literal body: People, "F&O", 'QRM' */
    private static Set<String> topLevelKeys(String body) {
        Set<String> keys = new LinkedHashSet<>();
        Pattern key = Pattern.compile("\\G\\s*(?:\"([^\"]+)\"|'([^']+)'|([A-Za-z_$][\\w$]*))\\s*:");
        int depth = 0;
        boolean expectKey = true;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (depth == 0 && expectKey) {
                Matcher m = key.matcher(body);
                if (m.find(i)) {
                    keys.add(m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3));
                    i = m.end() - 1;
                    expectKey = false;
                    continue;
                }
            }
            if (c == '"' || c == '\'' || c == '`') {
                int end = body.indexOf(c, i + 1);
                while (end > 0 && body.charAt(end - 1) == '\\') {
                    end = body.indexOf(c, end + 1);
                }
                i = end;
            } else if (c == '{' || c == '[' || c == '(') {
                depth++;
            } else if (c == '}' || c == ']' || c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                expectKey = true;
            }
        }
        return keys;
    }
}
