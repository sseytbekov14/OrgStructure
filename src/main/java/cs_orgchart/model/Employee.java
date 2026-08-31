package cs_orgchart.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
