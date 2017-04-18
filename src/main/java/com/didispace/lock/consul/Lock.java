package com.didispace.lock.consul;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.kv.model.PutParams;
import com.ecwid.consul.v1.session.model.NewSession;
import com.ecwid.consul.v1.session.model.Session;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于Consul的互斥锁
 *
 * @author 翟永超
 * @create 2017/4/6.
 * @blog http://blog.didispace.com
 */
public class Lock {

    private static final String prefix = "lock/";  // 同步锁参数前缀

    private ConsulClient consulClient;
    private String sessionName;
    private String sessionId = null;
    private String lockKey;

    private CheckTtl checkTtl;  // Check Ttl

    /**
     *
     * @param consulClient
     * @param sessionName   同步锁的session名称
     * @param lockKey       同步锁在consul的KV存储中的Key路径，会自动增加prefix前缀，方便归类查询
     * @param checkTtl      对锁SessionTTL
     */
    public Lock(ConsulClient consulClient, String sessionName, String lockKey, CheckTtl checkTtl) {
        this.consulClient = consulClient;
        this.sessionName = sessionName;
        this.lockKey = prefix + lockKey;
        this.checkTtl = checkTtl;
    }

    /**
     * 获取同步锁
     *
     * @param block            是否阻塞，直到获取到锁为止，默认尝试间隔时间为500ms。
     * @return
     */
    public Boolean lock(boolean block) throws InterruptedException {
        return lock(block, 500L, null);
    }


    /**
     * 获取同步锁
     *
     * @param block            是否阻塞，直到获取到锁为止
     * @param timeInterval     block=true时有效，再次尝试的间隔时间
     * @param maxTimes         block=true时有效，最大尝试次数
     * @return
     */
    public Boolean lock(boolean block, Long timeInterval, Integer maxTimes) throws InterruptedException {
        if (sessionId != null) {
            throw new RuntimeException(sessionId + " - Already locked!");
        }
        sessionId = createSession(sessionName);
        int count = 1;
        while(true) {
            PutParams putParams = new PutParams();
            putParams.setAcquireSession(sessionId);
            if(consulClient.setKVValue(lockKey, "lock:" + LocalDateTime.now(), putParams).getValue()) {
                return true;
            } else if(block) {
                if(maxTimes != null && count >= maxTimes) {
                    return false;
                } else {
                    count ++;
                    if(timeInterval != null)
                        Thread.sleep(timeInterval);
                    continue;
                }
            } else {
                return false;
            }
        }
    }

    /**
     * 释放同步锁
     *
     * @return
     */
    public Boolean unlock() {
        if(checkTtl != null) {
            checkTtl.stop();
        }

        PutParams putParams = new PutParams();
        putParams.setReleaseSession(sessionId);
        boolean result = consulClient.setKVValue(lockKey, "unlock:" + LocalDateTime.now(), putParams).getValue();
        consulClient.sessionDestroy(sessionId, null);
        return result;
    }

    /**
     * 创建session
     * @param sessionName
     * @return
     */
    private String createSession(String sessionName) {
        NewSession newSession = new NewSession();
        newSession.setName(sessionName);
        if(checkTtl != null) {
            checkTtl.start();
            // 如果有CheckTtl，就为该Session设置Check相关信息
            List<String> checks = new ArrayList<>();
            checks.add(checkTtl.getCheckId());
            newSession.setChecks(checks);
            newSession.setBehavior(Session.Behavior.DELETE);
            /** newSession.setTtl("60s");
            指定秒数（10s到86400s之间）。如果提供，在TTL到期之前没有更新，则会话无效。
             应使用最低的实际TTL来保持管理会话的数量。
             当锁被强制过期时，例如在领导选举期间，会话可能无法获得最多双倍TTL，
             因此应避免长TTL值（> 1小时）。**/
        }
        return consulClient.sessionCreate(newSession, null).getValue();
    }

}
