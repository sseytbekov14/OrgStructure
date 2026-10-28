package cs_orgchart.employee.web;

import cs_orgchart.employee.override.OverrideAdminService;
import cs_orgchart.employee.override.OverrideAdminViews.HeadsRequest;
import cs_orgchart.employee.override.OverrideAdminViews.HeadsView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The heads of a function for admins (spec v3 §10.2; U1: /api/manage/**, {@link cs_orgchart.access.AdminAccess}).
 * 409 with EMPLOYEE_SOURCE=excel.
 */
@RestController
@RequestMapping("/api/manage/functions/{cs}/heads")
public class FunctionHeadsAdminController {

    private final OverrideAdminService service;

    public FunctionHeadsAdminController(OverrideAdminService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public HeadsView heads(@PathVariable String cs) {
        return service.heads(cs);
    }

    @PutMapping
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public HeadsView setHeads(@PathVariable String cs, @RequestBody HeadsRequest request, Authentication admin) {
        return service.setHeads(cs, request, admin.getName());
    }
}
