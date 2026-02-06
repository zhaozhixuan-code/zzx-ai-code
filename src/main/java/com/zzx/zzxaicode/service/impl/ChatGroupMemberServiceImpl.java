package com.zzx.zzxaicode.service.impl;

import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.zzx.zzxaicode.model.po.ChatGroupMember;
import com.zzx.zzxaicode.mapper.ChatGroupMemberMapper;
import com.zzx.zzxaicode.service.ChatGroupMemberService;
import org.springframework.stereotype.Service;

/**
 * 群组成员表 服务层实现。
 *
 * @author <a href="https://github.com/zhaozhixuan-code/">zhaozhixuan</a>
 */
@Service
public class ChatGroupMemberServiceImpl extends ServiceImpl<ChatGroupMemberMapper, ChatGroupMember>  implements ChatGroupMemberService{

}
