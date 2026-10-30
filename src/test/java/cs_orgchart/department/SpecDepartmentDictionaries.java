package cs_orgchart.department;

import java.util.List;
import java.util.Map;

/**
 * The dictionaries of spec v3 §5.3 and §5.5 in memory, for the tests without a database.
 * {@code JpaDepartmentDictionariesTest} checks that the migration V4 seeds exactly these.
 */
public final class SpecDepartmentDictionaries implements DepartmentDictionaries {

    public static final SpecDepartmentDictionaries INSTANCE = new SpecDepartmentDictionaries();

    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("cs", "Central Services"),
            Map.entry("central services", "Central Services"),
            Map.entry("f&o", "Facilities & Operations"),
            Map.entry("facilities & operations", "Facilities & Operations"),
            Map.entry("ogc", "Office of General Counsel"),
            Map.entry("office of general counsel", "Office of General Counsel"),
            Map.entry("office general counsel", "Office of General Counsel"),
            Map.entry("hr admin", "HR Administration"),
            Map.entry("hr administration", "HR Administration"),
            Map.entry("graduate", "Graduate Recruiting"),
            Map.entry("graduate recruiting", "Graduate Recruiting"),
            Map.entry("tenders", "Proposals & Tenders"),
            Map.entry("proposals & tenders", "Proposals & Tenders"),
            Map.entry("accounting general", "Accounting General"),
            Map.entry("brand & hospitality", "Brand & Hospitality"),
            Map.entry("compensation & benefits", "Compensation & Benefits"),
            Map.entry("designers markets", "Designers Markets"),
            Map.entry("it operations", "IT Operations"),
            Map.entry("it pmo", "IT PMO"),
            Map.entry("l&d", "L&D"),
            Map.entry("talent acquisition", "Talent Acquisition"));

    private static final Map<String, String> GENERAL_NODES = Map.of(
            "ITS", "ITS General",
            "QRM", "QRM General",
            "Finance", "Finance General",
            "Markets", "Markets General",
            "People", "People General",
            "Facilities & Operations", "F&O General",
            "Office of General Counsel", "OGC General",
            "Procurement", "Procurement General",
            "Executives", "Administration Office");

    private static final List<FunctionMapping> FUNCTION_MAP = List.of(
            new FunctionMapping("Facilities & Operations", null, "F&O", true),
            new FunctionMapping("Executives", null, "F&O", true),
            new FunctionMapping("Procurement", null, "F&O", true),
            new FunctionMapping("Office of General Counsel", null, "OGC", true),
            new FunctionMapping("People", null, "People", true),
            new FunctionMapping("Finance", null, "Finance", true),
            new FunctionMapping("ITS", null, "ITS", true),
            new FunctionMapping("Markets", null, "Markets", true),
            new FunctionMapping("QRM", null, "QRM", true));

    private SpecDepartmentDictionaries() {
    }

    @Override
    public Map<String, String> aliases() {
        return ALIASES;
    }

    @Override
    public Map<String, String> generalNodes() {
        return GENERAL_NODES;
    }

    @Override
    public List<FunctionMapping> functionMappings() {
        return FUNCTION_MAP;
    }
}
