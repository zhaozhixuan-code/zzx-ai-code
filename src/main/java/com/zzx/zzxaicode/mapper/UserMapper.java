package com.zzx.zzxaicode.mapper;

import com.zzx.zzxaicode.model.po.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
* @author 28299
* @description 针对表【user(用户)】的数据库操作Mapper
* @createDate 2025-11-19 20:19:00
* @Entity com.zzx.zzxaicode.model.po.User
*/
@Mapper
public interface UserMapper extends BaseMapper<User> {

}




