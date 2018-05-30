package com.cayzlh.distributedlock.lock;

/**
 * Description:
 *
 * <p>
 *     为了可以以后扩展为使用其他方式来实现分布式锁，定义接口
 * </p>
 *
 * @author Ant丶
 * @date 2018-05-29.
 */
public interface IDistributedLock {
    public static final long TIMEOUT_MILLIS = 5000;

    public static final int RETRY_TIMES = Integer.MAX_VALUE;

    public static final long SLEEP_MILLIS = 500;

    public boolean lock(String key);

    public boolean lock(String key, int retryTimes);

    public boolean lock(String key, int retryTimes, long sleepMillis);

    public boolean lock(String key, long expire);

    public boolean lock(String key, long expire, int retryTimes);

    public boolean lock(String key, long expire, int retryTimes, long sleepMillis);

    public boolean releaseLock(String key);
}
