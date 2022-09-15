package com.jianghaotian.mybatis;

import com.jianghaotian.mybatis.mapper.UserMapper;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.sql.*;

/**
 * 描述:
 * 公司: 纽睿科技
 * 项目: mybatis-3.5.10
 * 创建时间: 2022/9/15 11:49
 *
 * @author jianghaotian
 */
public class Test {
    public static void main(String[] args) throws IOException {

        //JDBC的弊端非常明显
        //1.数据库连接创建，释放频繁造成系统资源的浪费，从而影响系统性能，使用数据库连接池可以解决问题。
        //2.sql语句在代码中硬编码，造成代码的不已维护，实际应用中sql的变化可能较大，sql代码和java代码没有分离开来维护不方便。
        //3.使用preparedStatement向有占位符传递参数存在硬编码问题因为sql中的where子句的条件不确定，同样是修改不方便.
        //4.对结果集中解析存在硬编码问题，sql的变化导致解析代码的变化，系统维护不方便。

        //为了解决上述问题,Mybatis,Hibernate等ORM框架诞生

        //Mybatis称之为半自动的ORM框架(O-Object,也就是对象,R-relational,关系型数据库,M-Mapping,映射)
        //称之为半自动的原因是mybatis要使用自定义sql的方式,手动编写sql语句来对数据库进行操作,这是它的优势,灵活性强,能写出复杂的sql
        //像Hibernate,JPA都是全自动的ORM框架,只需要配置好映射,使用面向对象的方式就可以操作数据库,缺点就是书写复杂的sql比较麻烦
        //国内相比国外来说,业务更复杂,关系也相对复杂,所以mybatis更受欢迎,国外则使用Hibernate较多

        //mybatis架构分为3大层
        //1.API接口层:提供给外部的API接口(增删改查),程序员可以利用这些API来操作数据库,接口层收到调用请求就会去调用数据处理层来哇成具体的数据处理.
        //2.数据处理层:负责具体的SQL查找,SQL解析,SQL执行以及返回结果的映射处理等,这一层的作用是根据调用的请求完成一次数据库操作.
        //3.基础支撑层:负责最基础的功能支撑,例如连接管理,事务管理,配置加载和缓存处理,这些共用的东西,将他们抽取出来作为最基础的组件,为上层的数据处理层提供最基础的支撑.





        //将XML配置文件构建为Configuration配置类
        InputStream inputStream = Resources.getResourceAsStream("mybatis.xml");
        //通过加载配置文件流构建一个SqlSessionFactory,具体是一个DefaultSqlSessionFactory
        //进入build()方法查看注释
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);
        SqlSession sqlSession = sqlSessionFactory.openSession();

        //此处的UserMapper是mybatis生成的UserMapper代理对象
        UserMapper mapper = sqlSession.getMapper(UserMapper.class);
        String result = mapper.select();

        System.out.println(result);

        sqlSession.commit();
        sqlSession.flushStatements();
        sqlSession.close();
    }

    /**
     * JDBC的弊端非常明显
     * 1.数据库配置,sql语句在代码中硬编码 维护性差
     * 2.jdbc频繁创建和关闭数据库连接,资源消耗大
     * 3.无缓存机制
     * 4.sql中的入参不方便
     * 5.处理查询结果集不方便
     * <p>
     * mybatis对应的解决方案
     * 1.xml或者properties文件
     * 2.连接池
     * 3.一级二级缓存
     * 4.#{} <if></if>
     * 5.resultMap
     */
    public static void JDBCTest() {
        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            //利用SPI机制,DriverManager的静态代码块里会加载JDBC驱动,无需使用Class.forName()进行手动注册
            conn = DriverManager.getConnection("", "", "");

            conn.setAutoCommit(false);

            String sql = "select * from user where id = ?";

            stmt = conn.prepareStatement(sql);
            stmt.setInt(1, 1);

            stmt.execute();
            ResultSet rs = stmt.getResultSet();
            rs.next();

            //处理返回结果
            long id = rs.getLong("id");
            String name = rs.getString("name");
            System.out.println("id:" + id + ",name:" + name);

            //提交事务
            conn.commit();

        } catch (Exception e) {
            e.printStackTrace();

            //回滚事务
            try {
                conn.rollback();
            } catch (SQLException ex) {
                ex.printStackTrace();
            }
        }
    }
}
