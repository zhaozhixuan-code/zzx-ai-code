package com.zzx.zzxaicode.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IORuntimeException;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.zzx.zzxaicode.constants.AppConstant;
import com.zzx.zzxaicode.core.AiCodeGeneratorFacade;
import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import com.zzx.zzxaicode.exception.ThrowUtils;
import com.zzx.zzxaicode.model.dto.app.AppQueryRequest;
import com.zzx.zzxaicode.model.enums.CodeGenTypeEnum;
import com.zzx.zzxaicode.model.po.App;
import com.zzx.zzxaicode.mapper.AppMapper;
import com.zzx.zzxaicode.model.po.User;
import com.zzx.zzxaicode.model.vo.AppVO;
import com.zzx.zzxaicode.model.vo.UserVO;
import com.zzx.zzxaicode.service.AppService;
import com.zzx.zzxaicode.service.UserService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 应用 服务层实现。
 *
 * @author <a href="https://github.com/zhaozhixuan-code/">zhaozhixuan</a>
 */
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {


    // 用户服务
    @Resource
    private UserService userService;

    // AI 生成代码门面类
    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;


    /**
     * 获取 AI 生成代码
     *
     * @param appId     应用ID
     * @param message   消息
     * @param loginUser 登录用户
     * @return 生成结果流
     */
    @Override
    public Flux<String> chatToGenCode(Long appId, String message, User loginUser) {
        // 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(message == null || message.isEmpty(), ErrorCode.PARAMS_ERROR, "消息不能为空");
        // 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 验证用户是否有权限访问该应用，仅本人可以生成代码
        ThrowUtils.throwIf(!loginUser.getId().equals(app.getUserId()), ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        // 获取应用的代码生成类型
        String codeGenType = app.getCodeGenType();
        CodeGenTypeEnum genType = CodeGenTypeEnum.getEnumByValue(codeGenType);
        ThrowUtils.throwIf(genType == null, ErrorCode.PARAMS_ERROR, "代码生成类型不能为空");
        // 调用 AI 生成代码
        return aiCodeGeneratorFacade.generateAndSaveCodeStream(message, genType, appId);
    }

    /**
     * 获取 AppVO
     *
     * @param app
     * @return
     */
    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            return null;
        }
        AppVO appVO = new AppVO();
        BeanUtil.copyProperties(app, appVO);
        // 关联查询用户信息
        Long userId = app.getUserId();
        if (userId != null) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            appVO.setUser(userVO);
        }
        return appVO;
    }


    /**
     * 构造应用查询条件
     *
     * @param appQueryRequest
     * @return
     */
    @Override
    public QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest) {
        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = appQueryRequest.getId();
        String appName = appQueryRequest.getAppName();
        String cover = appQueryRequest.getCover();
        String initPrompt = appQueryRequest.getInitPrompt();
        String codeGenType = appQueryRequest.getCodeGenType();
        String deployKey = appQueryRequest.getDeployKey();
        Integer priority = appQueryRequest.getPriority();
        Long userId = appQueryRequest.getUserId();
        String sortField = appQueryRequest.getSortField();
        String sortOrder = appQueryRequest.getSortOrder();
        return QueryWrapper.create()
                .eq("id", id)
                .like("appName", appName)
                .like("cover", cover)
                .like("initPrompt", initPrompt)
                .eq("codeGenType", codeGenType)
                .eq("deployKey", deployKey)
                .eq("priority", priority)
                .eq("userId", userId)
                .orderBy(sortField, "ascend".equals(sortOrder));
    }

    /**
     * 获取应用封装列表
     *
     * @param appList
     * @return
     */
    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (CollUtil.isEmpty(appList)) {
            return new ArrayList<>();
        }
        // 批量获取用户信息，避免 N+1 查询问题
        Set<Long> userIds = appList.stream()
                .map(App::getUserId)
                .collect(Collectors.toSet());
        Map<Long, UserVO> userVOMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, userService::getUserVO));
        return appList.stream().map(app -> {
            AppVO appVO = getAppVO(app);
            UserVO userVO = userVOMap.get(app.getUserId());
            appVO.setUser(userVO);
            return appVO;
        }).collect(Collectors.toList());
    }

    /**
     * 应用部署
     *
     * @param appId     应用 ID
     * @param loginUser 登录用户
     * @return 部署返回的 URL 路径
     */
    @Override
    public String deployApp(Long appId, User loginUser) {
        // 1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        // 2. 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 校验用户是否有权限部署该应用
        ThrowUtils.throwIf(!loginUser.getId().equals(app.getUserId()), ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        // 4. 检查是否已经有 deployKey
        String deployKey = app.getDeployKey();
        if (StrUtil.isBlank(deployKey)) {
            deployKey = RandomUtil.randomString(6);
        }
        // 5. 检查原文件目录是否存在
        String codeGenType = app.getCodeGenType();
        String sourceDirName = codeGenType + "_" + appId;
        String sourceDirPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName;
        File sourceDir = new File(sourceDirPath);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用代码不存在，请先生成代码");
        }
        // 6. 复制文件到部署目录
        String deployDirPath = AppConstant.CODE_DEPLOY_ROOT_DIR + File.separator + deployKey;

        try {
            FileUtil.copyContent(sourceDir, new File(deployDirPath), true);
        } catch (IORuntimeException e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "部署失败：" + e.getMessage());
        }
        // 7. 更新部署的 deployKey 和 deployedTime
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setDeployKey(deployKey);
        updateApp.setDeployedTime(LocalDateTime.now());
        boolean update = this.updateById(updateApp);
        ThrowUtils.throwIf(!update, ErrorCode.SYSTEM_ERROR, "更新应用信息失败");
        // 8. 返回可访问的 URL
        return AppConstant.CODE_DEPLOY_HOST + "/" + deployKey + "/";
    }

}
