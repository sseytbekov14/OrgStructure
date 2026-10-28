package cs_orgchart.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One employee of the site as the API returns it (one row per person and function). From Excel
 * (EMPLOYEE_SOURCE=excel) or assembled from the database (EMPLOYEE_SOURCE=db, EmployeeDtoAssembler).
 * The fields from id on exist only in db mode and are left out of the JSON when empty, so the Excel answers keep
 * their format.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Employee {

    private String name;
    private String cs;
    private String group;
    private String jobTitle;
    private String email;
    private String pm;
    private String pmEmail;
    private String pmJobTitle;
    private String photoUrl;
    private int gradeOrder;
    private String areaOfDuties;
    private String jobDescription;

    /** Database id of the person (the same in each of their functions) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long id;

    /** {@link #id} of the effective parent when that person is on the site; external parents only via pm* */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long parentId;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String countryCode;

    /** Position of the group in the Groups view (U4: sort order of the function's groups); null: no group object */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer groupOrder;
}
