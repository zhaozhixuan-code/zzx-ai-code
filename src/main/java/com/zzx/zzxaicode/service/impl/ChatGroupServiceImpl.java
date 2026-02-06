package com.zzx.zzxaicode.service.impl;

import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.zzx.zzxaicode.model.po.ChatGroup;
import com.zzx.zzxaicode.mapper.ChatGroupMapper;
import com.zzx.zzxaicode.service.ChatGroupService;
import org.springframework.stereotype.Service;

/**
 * 对话房间 服务层实现。
 *
 * @author <a href="https://github.com/zhaozhixuan-code/">zhaozhixuan</a>
 */
@Service
public class ChatGroupServiceImpl extends ServiceImpl<ChatGroupMapper, ChatGroup>  implements ChatGroupService{

}
