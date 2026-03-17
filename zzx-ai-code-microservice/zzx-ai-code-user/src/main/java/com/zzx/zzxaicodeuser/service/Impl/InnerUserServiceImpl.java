package com.zzx.zzxaicodeuser.service.Impl;

import com.zzx.zzxaicode.innerservice.InnerUserService;
import com.zzx.zzxaicode.model.po.User;
import com.zzx.zzxaicode.model.vo.UserVO;
import com.zzx.zzxaicodeuser.service.UserService;
import jakarta.annotation.Resource;
import org.apache.dubbo.config.annotation.DubboService;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;

/**
 * 内部服务实现类，继承外部服务接口
 */
@DubboService
public class InnerUserServiceImpl implements InnerUserService {

    @Resource
    private UserService userService;

    @Override
    public List<User> listByIds(Collection<? extends Serializable> ids) {
        return userService.listByIds(ids);
    }

    @Override
    public User getById(Serializable id) {
        return userService.getById(id);
    }

    @Override
    public UserVO getUserVO(User user) {
        return userService.getUserVO(user);
    }
}
