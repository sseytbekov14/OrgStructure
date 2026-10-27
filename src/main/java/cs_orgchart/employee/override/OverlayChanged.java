package cs_orgchart.employee.override;

/**
 * An admin changed the overlay of a function, published inside the transaction of the change: the employee
 * directory is rebuilt after that transaction committed (DbEmployeeDirectory), never before.
 */
public record OverlayChanged(String cs) {
}
