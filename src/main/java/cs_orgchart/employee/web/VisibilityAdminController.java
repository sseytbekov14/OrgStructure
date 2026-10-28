package cs_orgchart.employee.web;

import cs_orgchart.employee.visibility.VisibilityService;
import cs_orgchart.employee.visibility.VisibilityService.HiddenView;
import cs_orgchart.employee.visibility.VisibilityService.HideRequest;
import cs_orgchart.employee.visibility.VisibilityService.HideResult;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Hide and restore people for admins (U4). 409 with EMPLOYEE_SOURCE=excel.
 */
@RestController
public class VisibilityAdminController {

    private final VisibilityService service;

    public VisibilityAdminController(VisibilityService service) {
        this.service = service;
    }

    @PostMapping("/api/manage/employees/{id}/hide")
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public HideResult hide(@PathVariable long id, @RequestBody(required = false) HideRequest request,
                           Authentication admin) {
        return service.hide(id, request, admin.getName());
    }

    @DeleteMapping("/api/manage/employees/{id}/hide")
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public ResponseEntity<Void> restore(@PathVariable long id, Authentication admin) {
        service.restore(id, admin.getName());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/manage/hidden")
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public List<HiddenView> hidden() {
        return service.hidden();
    }
}
