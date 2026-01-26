package com.zzx.zzxaicode.service.impl;

import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.zzx.zzxaicode.model.po.App;
import com.zzx.zzxaicode.mapper.AppMapper;
import com.zzx.zzxaicode.service.AppService;
import org.springframework.stereotype.Service;

/**
 * 应用 服务层实现。
 *
 * @author <a href="https://github.com/zhaozhixuan-code/">zhaozhixuan</a>
 */
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App>  implements AppService{

}
