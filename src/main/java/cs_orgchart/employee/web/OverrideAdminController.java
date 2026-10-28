package cs_orgchart.employee.web;

import cs_orgchart.employee.override.OverrideAdminService;
import cs_orgchart.employee.override.OverrideAdminViews.MemberView;
import cs_orgchart.employee.override.OverrideAdminViews.OverrideRequest;
import cs_orgchart.employee.override.OverrideAdminViews.OverrideView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Overlay of a person in a function for admins (spec v3 §10.2; U1: /api/manage/**, database-managed admins and the
 * env super admins, checked by {@link cs_orgchart.access.AdminAccess} on every method). 409 with EMPLOYEE_SOURCE=excel.
 */
@RestController
@RequestMapping("/api/manage/overrides")
public class OverrideAdminController {

    private final OverrideAdminService service;

    public OverrideAdminController(OverrideAdminService service) {
        this.service = service;
    }

    /** The people of a function with their overlay row (cs empty: all people of the site), q filters */
    @GetMapping
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public List<MemberView> members(@RequestParam(required = false) String cs,
                                    @RequestParam(required = false) String q) {
        return service.members(cs == null || cs.isBlank() ? null : cs, q);
    }

    @PutMapping("/{employeeId}")
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public OverrideView upsert(@PathVariable Long employeeId, @RequestParam String cs,
                               @RequestBody OverrideRequest request, Authentication admin) {
        return service.upsert(employeeId, cs, request, admin.getName());
    }

    @DeleteMapping("/{employeeId}")
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public ResponseEntity<Void> delete(@PathVariable Long employeeId, @RequestParam String cs,
                                       @RequestParam(required = false) Long version,
                                       @RequestParam(defaultValue = "false") boolean confirmLastHead,
                                       Authentication admin) {
        service.delete(employeeId, cs, version, confirmLastHead, admin.getName());
        return ResponseEntity.noContent().build();
    }
}
