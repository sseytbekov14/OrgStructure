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

    /** Id of the group of this row; null: no group object (heads, people without a group) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long groupId;

    /** Position inside the group (or the Other column) set by an admin; null: by grade, after those with a position */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer groupPosition;

    /** U6: AD l */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String city;

    /** U6: AD co, else the English name of the country of AD c */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String country;

    /** U6: {@link #id} of the effective manager, also when they are outside Central Services */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long pmId;

    /** U6: the effective manager is outside Central Services (limited card, GET /api/people/{pmId}/external) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean pmExternal;

    /** The photo URL of the effective manager when they are outside Central Services (not in the directory) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String pmPhotoUrl;

    /** true: the photo shown was uploaded on the site (the photo dialog offers "Remove photo"); absent otherwise */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean photoUploaded;
}
