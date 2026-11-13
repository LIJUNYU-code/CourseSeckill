package com.example.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.seckill.entity.CourseStock;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface CourseStockMapper extends BaseMapper<CourseStock> {

    /** 普通读：不加锁。v0 的竞态就发生在这条 SELECT 与下面那条 UPDATE 之间 */
    @Select("SELECT * FROM course_stock WHERE course_id = #{courseId}")
    CourseStock selectByCourseId(@Param("courseId") Long courseId);

    @Update("UPDATE course_stock SET stock = stock + 1, sold = sold - 1 " +
            "WHERE course_id = #{courseId} AND sold > 0")
    int returnStock(@Param("courseId") Long courseId);

    /**
     * 🔴 v0 的"犯罪现场"：把 Java 里算好的绝对值写回去。
     * 它自己没错，错在调用它之前没有锁、没有版本校验。
     */
    @Update("UPDATE course_stock SET stock = #{newStock}, sold = sold + 1 WHERE course_id = #{courseId}")
    int absoluteUpdate(@Param("courseId") Long courseId, @Param("newStock") int newStock);

    /**
     * 条件扣减：判断（stock > 0）与扣减在同一条 SQL 内部完成，数据库保证原子性。
     *
     * @return 受影响行数。1 = 扣减成功；0 = 库存不足或课程不存在
     */
    @Update("UPDATE course_stock SET stock = stock - 1, sold = sold + 1 " +
            "WHERE course_id = #{courseId} AND stock > 0")
    int tryDecrement(@Param("courseId") Long courseId);

    /**
     * 加排他锁读取库存行。
     * 必须在一个事务里调用——事务结束（提交或回滚）时锁才释放。
     */
    @Select("SELECT * FROM course_stock WHERE course_id = #{courseId} FOR UPDATE")
    CourseStock selectForUpdate(@Param("courseId") Long courseId);

    /**
     * 版本号 CAS 扣减：只有当 version 还等于读到的那个值时，扣减才生效。
     *
     * @return 受影响行数。1 = 抢到了；0 = 版本已变（别人先改过）或库存不足
     */
    @Update("UPDATE course_stock SET stock = stock - 1, sold = sold + 1, version = version + 1 " +
            "WHERE course_id = #{courseId} AND version = #{version} AND stock > 0")
    int casDecrement(@Param("courseId") Long courseId, @Param("version") Integer version);
}

