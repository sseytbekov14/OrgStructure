package cs_orgchart.employee;

import cs_orgchart.department.DepartmentAliasRepository;
import cs_orgchart.department.DepartmentFunctionMapRepository;
import cs_orgchart.department.DepartmentGeneralNodeRepository;
import cs_orgchart.employee.override.EmployeeOverrideRepository;
import cs_orgchart.service.OrgStreamService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import cs_orgchart.employee.group.FunctionGroupRepository;
import cs_orgchart.employee.group.GroupMemberOrderRepository;
import cs_orgchart.employee.visibility.EmployeeVisibilityRepository;
import cs_orgchart.photo.EmployeePhotoInfoRepository;
import cs_orgchart.photo.PhotoProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Where the employee directory comes from: EMPLOYEE_SOURCE=excel (ExcelService, as before) or db
 * ({@link DbEmployeeDirectory}).
 */
@Configuration
@EnableConfigurationProperties({EmployeeSourceProperties.class, PhotoProperties.class})
public class EmployeeDirectoryConfig {

    @Bean
    @ConditionalOnProperty(name = "app.employees.source", havingValue = "db")
    public DbEmployeeDirectory dbEmployeeDirectory(EmployeeRepository employees, EmployeeOverrideRepository overlay,
                                                   DepartmentAliasRepository aliases,
                                                   DepartmentGeneralNodeRepository generalNodes,
                                                   DepartmentFunctionMapRepository functionMap, EmployeePhotos photos,
                                                   EmployeeDirectory directory, OrgStreamService orgStream,
                                                   PlatformTransactionManager transactions,
                                                   EmployeeSourceProperties source, FunctionGroupRepository groups,
                                                   EmployeeVisibilityRepository visibility,
                                                   PhotoProperties photoSettings,
                                                   EmployeePhotoInfoRepository adPhotos,
                                                   GroupMemberOrderRepository memberOrder) {
        return new DbEmployeeDirectory(employees, overlay, aliases, generalNodes, functionMap, photos, directory,
                orgStream, transactions, source.orgParentSource(), groups, visibility, photoSettings, adPhotos,
                memberOrder);
    }
}
