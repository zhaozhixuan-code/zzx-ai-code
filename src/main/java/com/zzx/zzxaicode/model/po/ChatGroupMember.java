package com.zzx.zzxaicode.model.po;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;
import java.io.Serializable;
import java.time.LocalDateTime;

import java.io.Serial;

import com.mybatisflex.core.keygen.KeyGenerators;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 群组成员表 实体类。
 *
 * @author <a href="https://github.com/zhaozhixuan-code/">zhaozhixuan</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("chat_group_member")
public class ChatGroupMember implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * id
     */
    @Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)
    private Long id;

    /**
     * 群组ID
     */
    @Column("groupId")
    private Long groupId;

    /**
     * 用户ID
     */
    @Column("userId")
    private Long userId;

    /**
     * 成员角色：admin-管理员，member-普通成员
     */
    private String role;

    /**
     * 加入时间
     */
    @Column("joinTime")
    private LocalDateTime joinTime;

    /**
     * 退出时间
     */
    @Column("quitTime")
    private LocalDateTime quitTime;

    /**
     * 是否退出：0-未退出，1-已退出
     */
    @Column("isQuit")
    private Integer isQuit;

    /**
     * 创建时间
     */
    @Column("createTime")
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Column("updateTime")
    private LocalDateTime updateTime;

    /**
     * 是否删除
     */
    @Column(value = "isDelete", isLogicDelete = true)
    private Integer isDelete;

}
