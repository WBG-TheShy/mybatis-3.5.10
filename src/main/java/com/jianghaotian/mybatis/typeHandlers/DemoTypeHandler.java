package com.jianghaotian.mybatis.typeHandlers;

import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.TypeHandler;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 描述:
 * 公司: 纽睿科技
 * 项目: mybatis-3.5.10
 * 创建时间: 2022/9/15 16:34
 *
 * @author jianghaotian
 */
@MappedJdbcTypes(JdbcType.VARCHAR)
public class DemoTypeHandler implements TypeHandler<String> {
    @Override
    public void setParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {

    }

    @Override
    public String getResult(ResultSet rs, String columnName) throws SQLException {
        return null;
    }

    @Override
    public String getResult(ResultSet rs, int columnIndex) throws SQLException {
        return null;
    }

    @Override
    public String getResult(CallableStatement cs, int columnIndex) throws SQLException {
        return null;
    }
}
