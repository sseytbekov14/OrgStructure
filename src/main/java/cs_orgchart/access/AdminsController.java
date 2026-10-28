package cs_orgchart.access;

import cs_orgchart.access.AppAdminService.AddAdminRequest;
import cs_orgchart.access.AppAdminService.AdminView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The admin list for super admins only (U1). The env super admins (SYSTEM_ADMIN_EMAILS) are not in this list and
 * cannot be removed here. Works in both EMPLOYEE_SOURCE modes (access is not employee data).
 */
@RestController
@RequestMapping("/api/manage/admins")
public class AdminsController {

    private final AppAdminService service;

    public AdminsController(AppAdminService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@adminAccess.isSuperAdmin(authentication)")
    public List<AdminView> list() {
        return service.list();
    }

    @PostMapping
    @PreAuthorize("@adminAccess.isSuperAdmin(authentication)")
    public ResponseEntity<AdminView> add(@RequestBody AddAdminRequest request, Authentication superAdmin) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.add(request, superAdmin.getName()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@adminAccess.isSuperAdmin(authentication)")
    public ResponseEntity<Void> remove(@PathVariable long id, Authentication superAdmin) {
        service.remove(id, superAdmin.getName());
        return ResponseEntity.noContent().build();
    }
}
