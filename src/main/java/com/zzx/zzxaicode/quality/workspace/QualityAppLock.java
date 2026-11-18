package com.zzx.zzxaicode.quality.workspace;

import com.zzx.zzxaicode.exception.BusinessException;
import com.zzx.zzxaicode.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** 当前实例内覆盖生成、检查、修复、发布和部署的应用写入资格。 */
@Component
public class QualityAppLock {
    private final ConcurrentHashMap<Long, String> owners = new ConcurrentHashMap<>();

    /**
     * 获取应用独占资格；竞争失败的请求不会获得可释放别人的锁的句柄。
     * @param appId 已鉴权的应用 ID
     * @return 只可释放自身资格的租约
     */
    public Lease acquire(long appId) {
        String token = UUID.randomUUID().toString();
        if (owners.putIfAbsent(appId, token) != null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "当前应用正在生成、检查或部署，请稍后重试");
        }
        return new Lease(appId, token);
    }

    /** 任务实际停止写文件后才关闭租约；关闭幂等。 */
    public final class Lease implements AutoCloseable {
        private final long appId;
        private final String token;
        private final AtomicBoolean closed = new AtomicBoolean();

        /** @param appId 应用 ID @param token 当前持有者随机标识 */
        private Lease(long appId, String token) {
            this.appId = appId;
            this.token = token;
        }

        /** 释放本句柄实际持有的资格，不影响其他任务。 */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) owners.remove(appId, token);
        }
    }
}
