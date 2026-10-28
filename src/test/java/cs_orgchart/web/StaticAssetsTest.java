package cs_orgchart.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U2: the pages use the shared design system and components. No browser dialogs (alert / confirm / prompt) in any
 * page script: the dialog and toast components of ui.js replace them. No external fonts, CDNs or scripts (isolated
 * network, CSP default-src 'self').
 */
class StaticAssetsTest {

    private static final Path STATIC = Path.of("src/main/resources/static");
    private static final Path PAGES = Path.of("src/main/resources/pages");
    private static final Pattern BROWSER_DIALOG =
            Pattern.compile("(?<![\\w.$])(alert|confirm|prompt)\\s*\\(|\\bwindow\\s*\\.\\s*(alert|confirm|prompt)\\b");
    private static final Pattern EXTERNAL =
            Pattern.compile("(?:src|href)\\s*=\\s*[\"']\\s*(?:https?:)?//|@import|url\\(\\s*[\"']?\\s*(?:https?:)?//");

    private static List<Path> files(Path root, String extension) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(extension)).sorted().toList();
        }
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void noBrowserDialogsInAnyPageScript() throws IOException {
        List<String> found = new ArrayList<>();
        List<Path> scripts = new ArrayList<>(files(STATIC, ".js"));
        scripts.addAll(files(STATIC, ".html"));
        scripts.addAll(files(PAGES, ".html"));
        for (Path file : scripts) {
            String[] lines = read(file).split("\n");
            for (int i = 0; i < lines.length; i++) {
                String code = lines[i].replaceAll("//.*$", "");
                Matcher m = BROWSER_DIALOG.matcher(code);
                if (m.find()) {
                    found.add(file + ":" + (i + 1) + ": " + lines[i].trim());
                }
            }
        }
        assertThat(found).as("use confirmDialog() / toast() of ui.js").isEmpty();
    }

    @Test
    void theCheckWouldCatchThem() {
        assertThat(BROWSER_DIALOG.matcher("if (!confirm(\"x\")) return;").find()).isTrue();
        assertThat(BROWSER_DIALOG.matcher("alert(1)").find()).isTrue();
        assertThat(BROWSER_DIALOG.matcher("const v = window.prompt").find()).isTrue();
        assertThat(BROWSER_DIALOG.matcher("await confirmDialog({})").find()).isFalse();
        assertThat(BROWSER_DIALOG.matcher("onConfirm(x)").find()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"static/index.html", "static/function.html", "pages/admin.html",
            "static/access-not-granted.html", "static/sign-in-failed.html", "static/signed-out.html"})
    void everyPageUsesTheDesignSystemAndTheLogoSlot(String page) throws IOException {
        String html = read(Path.of("src/main/resources").resolve(page));
        assertThat(html).contains("/css/tokens.css", "/css/components.css", "/js/ui.js", "brand-logo");
        assertThat(html).doesNotContain("kpmg-logo\">KPMG</div>");
        assertThat(html).doesNotContainPattern("<style[\\s>]");
    }

    @Test
    void noExternalResources() throws IOException {
        List<Path> files = new ArrayList<>(files(STATIC, ".html"));
        files.addAll(files(PAGES, ".html"));
        files.addAll(files(STATIC, ".css"));
        for (Path file : files) {
            assertThat(EXTERNAL.matcher(read(file)).find()).as("%s loads something from outside the site", file).isFalse();
        }
    }

    @Test
    void theTokensHoldThePaletteAndTheFontStack() throws IOException {
        String tokens = read(STATIC.resolve("css/tokens.css"));
        assertThat(tokens).contains("--color-kpmg-blue: #00338d", "--color-medium-blue: #005eb8",
                "--font-family: Univers, \"Segoe UI\", Arial, sans-serif", "prefers-reduced-motion");
    }
}
