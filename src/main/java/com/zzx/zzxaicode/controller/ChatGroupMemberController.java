package com.zzx.zzxaicode.controller;

import com.mybatisflex.core.paginate.Page;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.beans.factory.annotation.Autowired;
import com.zzx.zzxaicode.model.po.ChatGroupMember;
import com.zzx.zzxaicode.service.ChatGroupMemberService;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/**
 * 群组成员表 控制层。
 *
 * @author <a href="https://github.com/zhaozhixuan-code/">zhaozhixuan</a>
 */
@RestController
@RequestMapping("/chatGroupMember")
public class ChatGroupMemberController {

    @Autowired
    private ChatGroupMemberService chatGroupMemberService;

    /**
     * 保存群组成员表。
     *
     * @param chatGroupMember 群组成员表
     * @return {@code true} 保存成功，{@code false} 保存失败
     */
    @PostMapping("save")
    public boolean save(@RequestBody ChatGroupMember chatGroupMember) {
        return chatGroupMemberService.save(chatGroupMember);
    }

    /**
     * 根据主键删除群组成员表。
     *
     * @param id 主键
     * @return {@code true} 删除成功，{@code false} 删除失败
     */
    @DeleteMapping("remove/{id}")
    public boolean remove(@PathVariable Long id) {
        return chatGroupMemberService.removeById(id);
    }

    /**
     * 根据主键更新群组成员表。
     *
     * @param chatGroupMember 群组成员表
     * @return {@code true} 更新成功，{@code false} 更新失败
     */
    @PutMapping("update")
    public boolean update(@RequestBody ChatGroupMember chatGroupMember) {
        return chatGroupMemberService.updateById(chatGroupMember);
    }

    /**
     * 查询所有群组成员表。
     *
     * @return 所有数据
     */
    @GetMapping("list")
    public List<ChatGroupMember> list() {
        return chatGroupMemberService.list();
    }

    /**
     * 根据主键获取群组成员表。
     *
     * @param id 群组成员表主键
     * @return 群组成员表详情
     */
    @GetMapping("getInfo/{id}")
    public ChatGroupMember getInfo(@PathVariable Long id) {
        return chatGroupMemberService.getById(id);
    }

    /**
     * 分页查询群组成员表。
     *
     * @param page 分页对象
     * @return 分页对象
     */
    @GetMapping("page")
    public Page<ChatGroupMember> page(Page<ChatGroupMember> page) {
        return chatGroupMemberService.page(page);
    }

}
