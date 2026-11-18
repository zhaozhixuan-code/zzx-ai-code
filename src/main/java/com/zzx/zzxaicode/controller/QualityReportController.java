package com.zzx.zzxaicode.controller;
import com.zzx.zzxaicode.common.*;
import com.zzx.zzxaicode.exception.*;
import com.zzx.zzxaicode.quality.*;
import com.zzx.zzxaicode.quality.model.QualityTaskSnapshot;
import com.zzx.zzxaicode.quality.workspace.QualityWorkspaceService;
import com.zzx.zzxaicode.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/** 报告与证据只向应用所有者开放；内部任务目录从公开静态资源路径隔离。 */
@RestController
@RequestMapping("/app/quality")
public class QualityReportController {
    private final QualityTaskStore store;
    private final AppService apps;
    private final UserService users;
    private final QualityWorkspaceService workspaces;
    /** @param store 报告仓储 @param apps 应用归属 @param users 登录态 @param workspaces 服务端任务目录 */
    public QualityReportController(QualityTaskStore store,AppService apps,UserService users,QualityWorkspaceService workspaces) {
        this.store=store; this.apps=apps; this.users=users; this.workspaces=workspaces;
    }
    /** @param appId 应用 @param taskId 可空，空时查询最近任务 @param request 登录请求 @return 完整轮次报告或 null */
    @GetMapping("/report")
    public BaseResponse<QualityTaskSnapshot> report(@RequestParam long appId,@RequestParam(required=false) String taskId,HttpServletRequest request) {
        authorize(appId,request);
        QualityTaskSnapshot task=taskId==null?store.latest(appId):store.find(validId(taskId));
        if(task!=null && task.appId()!=appId) throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"无权限查看此任务");
        return ResultUtils.success(task);
    }
    /** @param appId 应用 @param taskId 已保存任务 @param ref 报告中的服务端证据名 @param request 登录请求 @return 有界日志或截图 */
    @GetMapping("/evidence")
    public ResponseEntity<byte[]> evidence(@RequestParam long appId,@RequestParam String taskId,@RequestParam String ref,HttpServletRequest request) throws IOException {
        authorize(appId,request);
        QualityTaskSnapshot task=store.find(validId(taskId));
        if(task==null || task.appId()!=appId) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"任务不存在");
        if(!ref.matches("quality-(?:install|build|browser)-[0-9]+\\.(?:log|png)")) throw new BusinessException(ErrorCode.PARAMS_ERROR,"证据引用无效");
        Path path=workspaces.workspace(appId,taskId).source().resolve(ref);
        QualityWorkspaceService.requireNoLink(path);
        if(!Files.isRegularFile(path) || Files.size(path)>5*1024*1024) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(ref.endsWith(".png")?MediaType.IMAGE_PNG:MediaType.TEXT_PLAIN)
                .header("X-Content-Type-Options","nosniff").body(Files.readAllBytes(path));
    }
    /** @param appId 正整数应用 @param request 登录请求；所有者之外不泄露任务是否存在 */
    private void authorize(long appId,HttpServletRequest request) {
        if(appId<=0) throw new BusinessException(ErrorCode.PARAMS_ERROR,"应用标识无效");
        var user=users.getLoginUser(request); var app=apps.getById(appId);
        if(app==null) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR,"应用不存在");
        if(!app.getUserId().equals(user.getId())) throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"无权限查看质量报告");
    }
    /** @param id 请求任务标识 @return 规范 UUID，非法输入统一转换成参数异常 */
    private String validId(String id) {
        try { if(!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); return id; }
        catch(IllegalArgumentException error) { throw new BusinessException(ErrorCode.PARAMS_ERROR,"任务标识无效"); }
    }
}
