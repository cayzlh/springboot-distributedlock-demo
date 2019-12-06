> 基于springboot2.x，支持spring-boot-starter的船新版本：
> [redis-distributedlock-spring-boot-starter](https://github.com/cayzlh/redis-distributedlock-spring-boot-starter)

**本文详细地址：[SpringBoot实现Redis分布式锁](https://blog.cayzlh.com/2018/05/30/2018053001/)**

### 前言

分布式锁一般有三种实现方式：1. 数据库乐观锁；2. 基于Redis的分布式锁；3. 基于ZooKeeper的分布式锁。本文介绍基于Redis实现分布式锁。

### 可靠性([From](http://www.importnew.com/27477.html))

首先，为了确保分布式锁可用，我们至少要确保锁的实现同时满足以下四个条件：

1. 互斥性。在任意时刻，只有一个客户端能持有锁。
2. 不会发生死锁。即使有一个客户端在持有锁的期间崩溃而没有主动解锁，也能保证后续其他客户端能加锁。
3. 具有容错性。只要大部分的Redis节点正常运行，客户端就可以加锁和解锁。
4. 解铃还须系铃人。加锁和解锁必须是同一个客户端，客户端自己不能把别人加的锁给解了

### 实现

#### 创建一个SpringBoot工程

修改`pom.xml`文件, 添加如下依赖包:

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
        <exclusions>
            <exclusion>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-starter-logging</artifactId>
            </exclusion>
        </exclusions>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-log4j</artifactId>
        <version>1.3.8.RELEASE</version>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-cache</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-redis</artifactId>
        <version>1.4.7.RELEASE</version>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-aop</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

#### 定义一个注解类

```java
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface DistributeLock {

    /**
     * 锁的资源，key。
     *  支持spring El表达式
     */
    @AliasFor("name")
    String name() default "'default'";

    /**
     * 锁的资源，value。
     *  支持spring El表达式
     */
    @AliasFor("value")
    String value() default "'default'";

    /**
     * 持锁时间,单位毫秒
     */
    long keepMills() default 5000;

    /**
     * 当获取失败时候动作
     */
    LockFailAction action() default LockFailAction.CONTINUE;

    public enum LockFailAction{
        /** 放弃 */
        GIVEUP,
        /** 继续 */
        CONTINUE;
    }

    /**
     * 重试的间隔时间,设置GIVEUP忽略此项
     */
    long sleepMills() default 200;

    /**
     * 重试次数
     */
    int retryTimes() default 5;

}
```

#### 定义接口

```java
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
```

#### 定义抽象类

```java
public abstract class AbstractDistributedLockImpl implements IDistributedLock {

    @Override
    public boolean lock(String key) {
        return lock(key, TIMEOUT_MILLIS, RETRY_TIMES, SLEEP_MILLIS);
    }

    @Override
    public boolean lock(String key, int retryTimes) {
        return lock(key, TIMEOUT_MILLIS, retryTimes, SLEEP_MILLIS);
    }

    @Override
    public boolean lock(String key, int retryTimes, long sleepMillis) {
        return lock(key, TIMEOUT_MILLIS, retryTimes, sleepMillis);
    }

    @Override
    public boolean lock(String key, long expire) {
        return lock(key, expire, RETRY_TIMES, SLEEP_MILLIS);
    }

    @Override
    public boolean lock(String key, long expire, int retryTimes) {
        return lock(key, expire, retryTimes, SLEEP_MILLIS);
    }

}
```

#### 定义Redis分布式锁实现类

```java
public class RedisDistributedLock extends AbstractDistributedLockImpl {

    private static final Logger logger = getLogger(RedisDistributedLock.class);

    private RedisTemplate<Object, Object> redisTemplate;

    private ThreadLocal<String> lockFlag = new ThreadLocal<>();

    private static final String UNLOCK_LUA;

    private static final String SET_IF_NOT_EXIST = "NX";
    private static final String SET_WITH_EXPIRE_TIME = "PX";

    static {
        UNLOCK_LUA = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
    }

    public RedisDistributedLock(RedisTemplate<Object, Object> redisTemplate) {
        super();
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean lock(String key, long expire, int retryTimes, long sleepMillis) {
        boolean result = setRedis(key, expire);
        // 如果获取锁失败，按照传入的重试次数进行重试
        while((!result) && retryTimes-- > 0){
            try {
                logger.debug("lock failed, retrying..." + retryTimes);
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                return false;
            }
            result = setRedis(key, expire);
        }
        return result;
    }

    @Override
    public boolean releaseLock(String key) {
        // 释放锁的时候，有可能因为持锁之后方法执行时间大于锁的有效期，此时有可能已经被另外一个线程持有锁，所以不能直接删除
        try {
            List<String> keys = new ArrayList<>();
            keys.add(key);
            List<String> args = new ArrayList<>();
            args.add(lockFlag.get());

            // 使用lua脚本删除redis中匹配value的key，可以避免由于方法执行时间过长而redis锁自动过期失效的时候误删其他线程的锁
            // spring自带的执行脚本方法中，集群模式直接抛出不支持执行脚本的异常，所以只能拿到原redis的connection来执行脚本

            Long result = redisTemplate.execute((RedisCallback<Long>) redisConnection -> {
                Object nativeConnection = redisConnection.getNativeConnection();
                // 集群模式和单机模式虽然执行脚本的方法一样，但是没有共同的接口，所以只能分开执行
                // 集群模式
                if (nativeConnection instanceof JedisCluster) {
                    return (Long) ((JedisCluster) nativeConnection).eval(UNLOCK_LUA, keys, args);
                }

                // 单机模式
                else if (nativeConnection instanceof Jedis) {
                    return (Long) ((Jedis) nativeConnection).eval(UNLOCK_LUA, keys, args);
                }
                return 0L;
            });

            return result != null && result > 0;
        } catch (Exception e) {
            logger.error("release lock occured an exception", e);
        } finally {
            // 清除掉ThreadLocal中的数据，避免内存溢出
            lockFlag.remove();
        }
        return false;
    }

    private boolean setRedis(String key, long expire) {
        try {
            String result = redisTemplate.execute((RedisCallback<String>) redisConnection -> {
                JedisCommands commands = (JedisCommands) redisConnection.getNativeConnection();
                String uuid = UUID.randomUUID().toString();
                lockFlag.set(uuid);
                return commands.set(key, uuid, SET_IF_NOT_EXIST, SET_WITH_EXPIRE_TIME, expire);
            });
            return !StringUtils.isEmpty(result);
        } catch (Exception e) {
            logger.error("set redis occured an exception", e);
        }
        return false;
    }

}
```

#### 装配DistributeLock

```java
@Configuration
@AutoConfigureAfter(RedisAutoConfiguration.class)
public class DistributedLockAutoConfiguration {

    @Bean
    @ConditionalOnBean(RedisTemplate.class)
    public IDistributedLock redisDistributedLock(RedisTemplate<Object, Object> redisTemplate){
        return new RedisDistributedLock(redisTemplate);
    }

}
```

#### 定义切面

```java
@Aspect
@Configuration
@ConditionalOnClass(IDistributedLock.class)
@AutoConfigureAfter(DistributedLockAutoConfiguration.class)
public class DistributedLockAspectConfiguration {

    private static final Logger logger = getLogger(DistributedLockAspectConfiguration.class);

    @Autowired
    private IDistributedLock distributedLock;

    private ExpressionParser parser = new SpelExpressionParser();

    private LocalVariableTableParameterNameDiscoverer discoverer = new LocalVariableTableParameterNameDiscoverer();

    /**
     * 定义切入点
     */
    @Pointcut("@annotation(com.cayzlh.distributedlock.annotations.DistributeLock)")
    private void lockPoint() {
    }

    /**
     * 环绕通知
     *
     * @param pjp pjp
     * @return  方法返回结果
     * @throws Throwable throwable
     */
    @Around("lockPoint()")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        DistributeLock lockAction = method.getAnnotation(DistributeLock.class);
        String logKey = getLogKey(lockAction, pjp, method);

        int retryTimes = lockAction.action().equals(DistributeLock.LockFailAction.CONTINUE) ? lockAction.retryTimes() : 0;
        boolean lock = distributedLock.lock(logKey, lockAction.keepMills(), retryTimes, lockAction.sleepMills());
        if (!lock) {
            logger.debug("get lock failed : " + logKey);
            return null;
        }

        //得到锁,执行方法，释放锁
        logger.debug("get lock success : " + logKey);
        try {
            return pjp.proceed();
        } catch (Exception e) {
            logger.error("execute locked method occured an exception", e);
        } finally {
            boolean releaseResult = distributedLock.releaseLock(logKey);
            logger.debug("release lock : " + logKey + (releaseResult ? " success" : " failed"));
        }
        return null;
    }

    /**
     * 获得分布式缓存的key
     *
     * @param lockAction 注解对象
     * @param pjp        pjp
     * @param method     method
     * @return String
     */
    private String getLogKey(DistributeLock lockAction, ProceedingJoinPoint pjp, Method method) {
        String name = lockAction.name();
        String value = lockAction.value();
        Object[] args = pjp.getArgs();
        return parse(name, method, args) + "_" + parse(value, method, args);
    }

    /**
     * 解析spring EL表达式
     *
     * @param key    key
     * @param method method
     * @param args   args
     * @return parse result
     */
    private String parse(String key, Method method, Object[] args) {
        String[] params = discoverer.getParameterNames(method);
        if (null == params || params.length == 0 || !key.contains("#")) {
            return key;
        }
        EvaluationContext context = new StandardEvaluationContext();
        for (int i = 0; i < params.length; i++) {
            context.setVariable(params[i], args[i]);
        }
        return parser.parseExpression(key).getValue(context, String.class);
    }

}
```

#### 配置文件

```properties
server.port=8080

spring.redis.host=127.0.0.1
spring.redis.port=6379
spring.redis.jedis.pool.max-idle=8
spring.redis.jedis.pool.min-idle=0
spring.redis.jedis.pool.max-active=8
spring.redis.jedis.pool.max-wait=-1ms
spring.redis.timeout=20ms
spring.redis.password=
```

#### 配置log4j配置文件

```properties
# server默认为空
server=
# 日志输出目录
logFilePath=logs
log4j.rootCategory=DEBUG,stdout,debugLog,infoLog,errorLog

# 控制台日志输出
log4j.logger.consoleLogger=stdout

log4j.appender.stdout=org.apache.log4j.ConsoleAppender
log4j.appender.stdout.Threshold=DEBUG
log4j.appender.stdout.layout=org.apache.log4j.PatternLayout
log4j.appender.stdout.layout.ConversionPattern=[%p] %d %c - %m%n
log4j.appender.stdout.ImmediateFlush=true

# debug日志输出
log4j.logger.debugLog=DEBUG, debugLog

log4j.appender.debugLog=org.apache.log4j.DailyRollingFileAppender
log4j.appender.debugLog.File=${logFilePath}/debug.log
log4j.appender.debugLog.layout=org.apache.log4j.PatternLayout
log4j.appender.debugLog.layout.ConversionPattern=%d{yyyy-MM-dd HH:mm:ss,SSS} %5p %c{1}:%L - %m%n
log4j.appender.debugLog.DatePattern='.'yyyy-MM-dd
log4j.appender.debugLog.ImmediateFlush=true
log4j.appender.debugLog.Threshold=DEBUG
log4j.appender.debugLog.encoding=UTF-8
log4j.appender.debugLog.filter.debugFilter=org.apache.log4j.varia.LevelRangeFilter
log4j.appender.debugLog.filter.debugFilter.LevelMin=DEBUG
log4j.appender.debugLog.filter.debugFilter.LevelMax=DEBUG
```