package com.example.seckill.config;

import com.example.seckill.infra.cache.StockLuaScript;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 相关 Bean。
 * <p>
 * 这里只放 Lua 脚本的 Bean，不定义 StringRedisTemplate ——
 * Spring Boot 的 RedisAutoConfiguration 已经自动配好了，
 * 再定义一遍会覆盖它，application.yaml 里的 host / port 就失效了。
 */
@Configuration
public class RedisConfig {

    /**
     * 库存扣减脚本。
     * <p>
     * 方法名就是 Bean 名，必须和 StockCacheServiceImpl 里那个
     * private final DefaultRedisScript&lt;Long&gt; stockDecrementScript; 字段名一致，
     * 否则 Spring 按名字注入时找不到，启动报 No qualifying bean。
     */
    @Bean
    public DefaultRedisScript<Long> stockDecrementScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(StockLuaScript.DECREMENT);
        // 不设这一行，execute 的返回值是 Object，赋值给 Long 时编译不过
        script.setResultType(Long.class);
        return script;
    }
}
