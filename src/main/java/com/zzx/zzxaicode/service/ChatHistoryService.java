package com.zzx.zzxaicode.service;

import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import com.zzx.zzxaicode.model.chathistory.ChatHistoryQueryRequest;
import com.zzx.zzxaicode.model.po.ChatHistory;
import com.zzx.zzxaicode.model.po.User;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;

import java.time.LocalDateTime;

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
     * 获取对话消息
     *
     * @param appid      应用ID
     * @param chatMemory 会话内存
     * @param maxCount   最大数量
     * @return 获得到的对话数量
     */
    int loadChatHistoryToMemory(Long appid, MessageWindowChatMemory chatMemory, int maxCount);

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
    QueryWrapper getQueryWrapper(ChatHistoryQueryRequest chatHistoryQueryRequest);

}
