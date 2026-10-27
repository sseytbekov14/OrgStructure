package cs_orgchart.importer;

/**
 * The import cannot run (file, sheet or column missing): nothing is changed and the report says why.
 */
public class ImportException extends RuntimeException {

    public ImportException(String message) {
        super(message);
    }
}
