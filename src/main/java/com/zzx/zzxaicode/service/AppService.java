package com.zzx.zzxaicode.service;

import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.zzx.zzxaicode.model.dto.app.AppQueryRequest;
import com.zzx.zzxaicode.model.po.App;
import com.zzx.zzxaicode.model.po.User;
import com.zzx.zzxaicode.model.vo.AppVO;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 应用 服务层。
 *
 * @author <a href="https://github.com/zhaozhixuan-code/">zhaozhixuan</a>
 */
public interface AppService extends IService<App> {

    /**
     * 获取 AI 生成代码
     *
     * @param appId     应用ID
     * @param message   消息
     * @param loginUser 登录用户
     * @return 生成结果流
     */
    Flux<String> chatToGenCode(Long appId, String message, User loginUser);

    /**
     * 获取 AppVO
     *
     * @param app
     * @return
     */
    AppVO getAppVO(App app);

    /**
     * 构造应用查询条件
     *
     * @param appQueryRequest
     * @return
     */
    QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest);

    /**
     * 获取应用封装列表
     *
     * @param appList
     * @return
     */
    List<AppVO> getAppVOList(List<App> appList);

    /**
     * 应用部署
     *
     * @param appId     应用 ID
     * @param loginUser 登录用户
     * @return 部署返回的 URL 路径
     */
    String deployApp(Long appId, User loginUser);

    /**
     * 生成应用截图
     *
     * @param appId        应用 ID
     * @param appDeployUrl 应用部署 URL
     */
    void generateAppScreenshot(Long appId, String appDeployUrl);
}
