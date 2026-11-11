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
}

