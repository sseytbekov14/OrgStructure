package cs_orgchart.department;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The dictionaries from the database, read on first use and cached; {@link #reload()} reads them again (before each
 * sync, so that a new AD spelling added to the tables applies without a redeploy).
 */
@Slf4j
public class JpaDepartmentDictionaries implements DepartmentDictionaries {

    private final DepartmentAliasRepository aliases;
    private final DepartmentGeneralNodeRepository generalNodes;
    private final DepartmentFunctionMapRepository functionMap;

    private volatile Snapshot snapshot;

    public JpaDepartmentDictionaries(DepartmentAliasRepository aliases, DepartmentGeneralNodeRepository generalNodes,
                                     DepartmentFunctionMapRepository functionMap) {
        this.aliases = aliases;
        this.generalNodes = generalNodes;
        this.functionMap = functionMap;
    }

    @Override
    public Map<String, String> aliases() {
        return current().aliases();
    }

    @Override
    public Map<String, String> generalNodes() {
        return current().generalNodes();
    }

    @Override
    public List<FunctionMapping> functionMappings() {
        return current().functionMappings();
    }

    public synchronized void reload() {
        Map<String, String> aliasMap = new LinkedHashMap<>();
        aliases.findAll().forEach(a -> aliasMap.put(a.getAlias().trim().toLowerCase(Locale.ROOT), a.getCanonical()));
        Map<String, String> generalMap = new LinkedHashMap<>();
        generalNodes.findAll().forEach(g -> generalMap.put(g.getFunctionName(), g.getGeneralName()));
        List<FunctionMapping> mappings = functionMap.findAll().stream()
                .map(m -> new FunctionMapping(m.getDeptLevel2(), m.getDeptLevel3(), m.getCsName(), m.isVisible()))
                .toList();
        snapshot = new Snapshot(Map.copyOf(aliasMap), Map.copyOf(generalMap), mappings);
        log.debug("Department dictionaries loaded: {} aliases, {} general nodes, {} function mappings",
                aliasMap.size(), generalMap.size(), mappings.size());
    }

    private Snapshot current() {
        Snapshot current = snapshot;
        if (current == null) {
            synchronized (this) {
                if (snapshot == null) {
                    reload();
                }
                current = snapshot;
            }
        }
        return current;
    }

    private record Snapshot(Map<String, String> aliases, Map<String, String> generalNodes,
                            List<FunctionMapping> functionMappings) {
    }
}
