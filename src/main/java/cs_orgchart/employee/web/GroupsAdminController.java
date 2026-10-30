package cs_orgchart.employee.web;

import cs_orgchart.employee.group.FunctionGroupService;
import cs_orgchart.employee.group.GroupViews.CreateGroupRequest;
import cs_orgchart.employee.group.GroupViews.GroupView;
import cs_orgchart.employee.group.GroupViews.GroupsView;
import cs_orgchart.employee.group.GroupViews.MemberOrderRequest;
import cs_orgchart.employee.group.GroupViews.MembersRequest;
import cs_orgchart.employee.group.GroupViews.MembersResult;
import cs_orgchart.employee.group.GroupViews.UpdateGroupRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Groups of a function and their members for admins (U4). 409 with EMPLOYEE_SOURCE=excel.
 */
@RestController
public class GroupsAdminController {

    private final FunctionGroupService service;

    public GroupsAdminController(FunctionGroupService service) {
        this.service = service;
    }

    @GetMapping("/api/manage/functions/{cs}/groups")
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public GroupsView groups(@PathVariable String cs) {
        return service.list(cs);
    }

    @PostMapping("/api/manage/functions/{cs}/groups")
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public ResponseEntity<GroupView> create(@PathVariable String cs, @RequestBody CreateGroupRequest request,
                                            Authentication admin) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(cs, request, admin.getName()));
    }

    @PutMapping("/api/manage/groups/{id}")
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public GroupView update(@PathVariable long id, @RequestBody UpdateGroupRequest request, Authentication admin) {
        return service.update(id, request, admin.getName());
    }

    @DeleteMapping("/api/manage/groups/{id}")
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public ResponseEntity<Void> delete(@PathVariable long id, @RequestParam(required = false) Long version,
                                       Authentication admin) {
        service.delete(id, version, admin.getName());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/manage/functions/{cs}/order")
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public ResponseEntity<Void> orderUngrouped(@PathVariable String cs, @RequestBody MemberOrderRequest request,
                                               Authentication admin) {
        service.orderUngrouped(cs, request, admin.getName());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/manage/functions/{cs}/order")
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public ResponseEntity<Void> resetUngroupedOrder(@PathVariable String cs, Authentication admin) {
        service.resetUngroupedOrder(cs, admin.getName());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/api/manage/groups/{id}/order")
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public ResponseEntity<Void> orderMembers(@PathVariable long id, @RequestBody MemberOrderRequest request,
                                             Authentication admin) {
        service.orderMembers(id, request, admin.getName());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/manage/groups/{id}/order")
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public ResponseEntity<Void> resetMemberOrder(@PathVariable long id, Authentication admin) {
        service.resetMemberOrder(id, admin.getName());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/manage/groups/{id}/members")
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public MembersResult addMembers(@PathVariable long id, @RequestBody MembersRequest request, Authentication admin) {
        return service.addMembers(id, request, admin.getName());
    }

    @DeleteMapping("/api/manage/groups/{id}/members/{employeeId}")
    @PreAuthorize("@adminAccess.isAdmin(authentication)")
    public ResponseEntity<Void> removeMember(@PathVariable long id, @PathVariable long employeeId,
                                             Authentication admin) {
        service.removeMember(id, employeeId, admin.getName());
        return ResponseEntity.noContent().build();
    }
}
