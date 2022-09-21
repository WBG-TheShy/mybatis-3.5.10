package com.jianghaotian.mybatis.mapper;

import org.apache.ibatis.annotations.Select;

/**
 * 描述:
 * 公司: 纽睿科技
 * 项目: mybatis-3.5.10
 * 创建时间: 2022/9/15 11:52
 *
 * @author jianghaotian
 */
public interface UserMapper {

    @Select("select 'selectByAnnotation'")
    String selectByAnnotation();

    String selectByXML();
}
