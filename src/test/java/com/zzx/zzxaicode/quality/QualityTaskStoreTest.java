package com.zzx.zzxaicode.quality;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzx.zzxaicode.controller.QualityReportController;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.model.po.*;
import com.zzx.zzxaicode.quality.model.*;
import com.zzx.zzxaicode.quality.workspace.QualityWorkspaceService;
import com.zzx.zzxaicode.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用独立 H2 实际事务验证任务及轮次；不连接项目配置的数据源。 */
class QualityTaskStoreTest {
    /** 同轮次幂等、终态不回退、重启标记和跨用户查询拒绝。 */
    @Test void persistsRoundsAndProtectsTerminalAndOwnership() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);
        String ddl=Files.readString(Path.of("sql/quality_check.sql"));
        for(String statement:ddl.split(";")) if(!statement.isBlank()) jdbc.execute(statement);
        var store=new JdbcQualityTaskStore(jdbc,new DataSourceTransactionManager(ds),new ObjectMapper().findAndRegisterModules(),"test-instance");
        String id=UUID.randomUUID().toString(); Instant now=Instant.now();
        var report=new QualityReport(id,1,0,now,now,List.of(new QualityCheckItem("build",true,QualityCheckItemStatus.PASSED,1,List.of())),"candidate");
        var running=new QualityTaskSnapshot(id,1,2,QualityTaskStatus.RUNNING,QualityTaskStage.CHECKING,0,2,now,null,"检查",null,List.of(report),List.of());
        store.create(running); store.save(running); store.save(running);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM quality_round",Integer.class));
        var done=new QualityTaskSnapshot(id,1,2,QualityTaskStatus.SUCCEEDED,QualityTaskStage.SUCCEEDED,0,2,now,now,"通过","candidate",List.of(report),List.of());
        store.save(done); store.save(done);
        assertThrows(IllegalStateException.class,()->store.save(running));
        assertEquals(done,store.latest(1));
        var interrupted=new QualityTaskSnapshot(UUID.randomUUID().toString(),3,2,QualityTaskStatus.RUNNING,QualityTaskStage.GENERATING,0,2,now,null,"生成",null,List.of(),List.of());
        store.create(interrupted); store.interruptUnfinished();
        assertEquals(QualityTaskStatus.INTERRUPTED,store.latest(3).status());
        assertEquals(done,store.find(id));

        var apps=mock(AppService.class);var users=mock(UserService.class);var req=new MockHttpServletRequest();
        User user=new User();user.setId(9L);App app=new App();app.setId(1L);app.setUserId(2L);
        when(users.getLoginUser(req)).thenReturn(user);when(apps.getById(1L)).thenReturn(app);
        var api=new QualityReportController(store,apps,users,new QualityWorkspaceService());
        assertThrows(BusinessException.class,()->api.report(1,id,req));
        user.setId(2L);assertEquals(done,api.report(1,id,req).getData());
        assertThrows(BusinessException.class,()->api.report(1,interrupted.taskId(),req));
        assertThrows(BusinessException.class,()->api.evidence(1,id,"../secret",req));
    }
}
