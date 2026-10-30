package cs_orgchart.employee.group;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;

/** The deletes run at once (not at the flush): the new positions are inserted right after them */
@Repository
public interface GroupMemberOrderRepository extends JpaRepository<GroupMemberOrder, GroupMemberOrder.Key> {

    @Modifying
    @Query("DELETE FROM GroupMemberOrder o WHERE o.groupId = :groupId")
    int deleteByGroupId(@Param("groupId") long groupId);

    /** The "Other" column of the function */
    @Modifying
    @Query("DELETE FROM GroupMemberOrder o WHERE o.cs = :cs AND o.groupId IS NULL")
    int deleteUngrouped(@Param("cs") String cs);

    /** Also the rows left from an earlier group of these people */
    @Modifying
    @Query("DELETE FROM GroupMemberOrder o WHERE o.cs = :cs AND o.employeeId IN :employeeIds")
    int deleteByCsAndEmployees(@Param("cs") String cs, @Param("employeeIds") Collection<Long> employeeIds);
}
