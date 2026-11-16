package com.zzx.zzxaicode.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zzx.zzxaicode.model.chathistory.ChatHistoryQueryRequest;
import com.zzx.zzxaicode.model.po.ChatHistory;
import com.zzx.zzxaicode.model.po.User;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话历史 服务层。
 *
 * @author <a href="https://github.com/zhaozhixuan-code/">zhaozhixuan</a>
 */
public interface ChatHistoryService extends IService<ChatHistory> {

    /**
     * 添加对话消息
     *
     * @param appId       应用ID
     * @param message     消息
     * @param messageType 消息类型
     * @param userId      用户ID
     * @return
     */
    boolean addChatMessage(Long appId, String message, String messageType, Long userId);

    /**
     * 查询应用最近的可见历史，不进行框架消息转换。
     *
     * @param appId 应用 ID
     * @param maxCount 最大消息数，必须大于零
     * @return 按创建时间及 ID 升序排列的历史；无历史时返回空列表
     */
    List<ChatHistory> listRecentMessages(Long appId, int maxCount);

    /**
     * 删除对话消息
     *
     * @param appId 应用ID
     * @return
     */
    boolean deleteByAppId(Long appId);


    /**
     * 分页获取应用对话消息
     *
     * @param appId          应用ID
     * @param pageSize       分页大小
     * @param lastCreateTime 最后创建时间（游标）
     * @param loginUser      登录用户
     * @return
     */
    Page<ChatHistory> listAppChatHistoryByPage(Long appId, int pageSize, LocalDateTime lastCreateTime, User loginUser);

    /**
     * 获取查询包装类
     *
     * @param chatHistoryQueryRequest
     * @return
     */
    QueryWrapper<ChatHistory> getQueryWrapper(ChatHistoryQueryRequest chatHistoryQueryRequest);

}
