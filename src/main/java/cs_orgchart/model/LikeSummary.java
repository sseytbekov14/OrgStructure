package cs_orgchart.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LikeSummary {

    private String employeeEmail;
    private long solvedCount;
    private long exceededCount;
}
