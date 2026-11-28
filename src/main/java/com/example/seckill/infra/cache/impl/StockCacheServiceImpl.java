package com.example.seckill.infra.cache.impl;

import com.example.seckill.common.ResultCode;
import com.example.seckill.entity.CourseStock;
import com.example.seckill.exception.BusinessException;
import com.example.seckill.infra.cache.StockCacheService;
import com.example.seckill.mapper.CourseStockMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@AllArgsConstructor
@Slf4j
public class StockCacheServiceImpl implements StockCacheService {

    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> stockDecrementScript;
    private final CourseStockMapper courseStockMapper;

    /** key 前缀统一在一处定义，避免五个方法各写一遍拼错。 */
    private String stockKey(Long courseId) {
        return "seckill:stock:" + courseId;
    }

    @Override
    public Long tryDecrement(Long courseId) {
        List<String> keys = List.of(stockKey(courseId));
        Long result;
        try {
            // 第三个参数是 ARGV[1]，Lua 里 tonumber(ARGV[1]) 会转成数字
            result = stringRedisTemplate.execute(stockDecrementScript, keys, "1");
        } catch (Exception e) {
            // 脚本报错抛的是 RedisSystemException，不是返回错误码。
            // 不能吞成 -2，否则 Redis 挂了会显示"名额已满"，掩盖真实故障。
            log.error("Lua 执行失败 courseId={}", courseId, e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "系统繁忙，请重试");
        }
        if (result == null) {
            // 不判的话下面拆箱比较时会 NPE，报错位置看起来莫名奇妙
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "库存脚本返回空");
        }
        return result;
    }

    @Override
    public void preheat(Long courseId) {
        CourseStock stock = courseStockMapper.selectByCourseId(courseId);
        if (stock == null) {
            log.warn("预热跳过：课程库存行不存在 courseId={}", courseId);
            return;
        }
        // 🔴 是 getStock() 不是 getCapacity()：
        //    capacity 是"总名额"，stock 是"还剩多少"，
        //    预热写 capacity 会让 Redis 里的库存永远是满的，超卖到天亮。
        stringRedisTemplate.opsForValue().set(stockKey(courseId), String.valueOf(stock.getStock()));
        log.info("预热完成 courseId={} stock={}", courseId, stock.getStock());
    }

    @Override
    public void preheatAll() {
        List<CourseStock> all = courseStockMapper.selectList(null);
        for (CourseStock s : all) {
            stringRedisTemplate.opsForValue().set(stockKey(s.getCourseId()), String.valueOf(s.getStock()));
        }
        log.info("预热完成，共 {} 门课程", all.size());
    }

    @Override
    public void increment(Long courseId) {
        stringRedisTemplate.opsForValue().increment(stockKey(courseId));
    }

    @Override
    public Long getStock(Long courseId) {
        String v = stringRedisTemplate.opsForValue().get(stockKey(courseId));
        return v == null ? null : Long.valueOf(v);
    }

    @Override
    public void clear(Long courseId) {
        stringRedisTemplate.delete(stockKey(courseId));
    }

    @Override
    public void incrementIfPresent(Long courseId) {
        String key = stockKey(courseId);
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            log.warn("退课归还跳过：Redis 未预热 courseId={}", courseId);
            return;
        }
        stringRedisTemplate.opsForValue().increment(key);
    }

    @Override
    public void preheatIfAbsent(Long courseId) {
        CourseStock stock = courseStockMapper.selectByCourseId(courseId);
        if (stock == null) {
            log.warn("预热跳过：课程库存行不存在 courseId={}", courseId);
            return;
        }
        Boolean written = stringRedisTemplate.opsForValue()
                .setIfAbsent(stockKey(courseId), String.valueOf(stock.getStock()));
        if (Boolean.TRUE.equals(written)) {
            log.info("预热完成（冷启动） courseId={} stock={}", courseId, stock.getStock());
        } else {
            log.debug("预热跳过：已由其它请求写入 courseId={}", courseId);
        }
    }
}
