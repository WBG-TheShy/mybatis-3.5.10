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
        //1.数据库连接创建，释放频繁造成系统资源的浪费，从而影响系统性能.
        //2.sql语句在代码中硬编码，造成代码的不易维护，实际应用中sql的变化可能较大，sql代码和java代码没有分离开来维护不方便。
        //3.使用preparedStatement向有占位符传递参数存在硬编码问题因为sql中的where子句的条件不确定，同样是修改不方便.
        //4.对结果集中解析存在硬编码问题，sql的变化导致解析代码的变化，系统维护不方便。

        //为了解决上述问题,Mybatis,Hibernate等ORM框架诞生

        //Mybatis对上述4个问题的解决方案
        //解决1.在SqlMapConfig.xml中配置数据连接池，使用连接池管理数据库链接。
        //解决2.将Sql语句配置在XXXXmapper.xml文件中与java代码分离。
        //解决3.Mybatis自动将java对象映射至sql语句，通过statement中的 parameterType定义输入参数的类型。
        //解决4.Mybatis自动将sql执行结果映射至java对象，通过statement中的 resultType定义输出结果的类型。

        //Mybatis称之为半自动的ORM框架(O-Object,也就是对象,R-relational,关系型数据库,M-Mapping,映射)
        //称之为半自动的原因是mybatis要使用自定义sql的方式,手动编写sql语句来对数据库进行操作,这是它的优势,灵活性强,能写出复杂的sql
        //像Hibernate,JPA都是全自动的ORM框架,只需要配置好映射,使用面向对象的方式就可以操作数据库,缺点就是书写复杂的sql比较麻烦
        //国内相比国外来说,业务更复杂,关系也相对复杂,所以mybatis更受欢迎,国外则使用Hibernate较多

        //mybatis架构分为3大层
        //1.API接口层:提供给外部的API接口(增删改查),程序员可以利用这些API来操作数据库,接口层收到调用请求就会去调用数据处理层来完成具体的数据处理.
        //           通过使用SqlSession来调用API,调用方式可以基于statementId或者基于mapper接口
        //2.数据处理层:负责具体的SQL查找,SQL解析,SQL执行以及返回结果的映射处理等,这一层的作用是根据调用的请求完成一次数据库操作.
        //           第一步:使用ParameterHandler进行参数的解析,映射
        //           第二步:通过SqlSource生成真正的SQL
        //           第三步:利用Exexutor来执行SQL
        //           第四步:最后将执行结果通过ResultSetHandler进行类型转换,映射成Java对象
        //3.基础支撑层:负责最基础的功能支撑,例如连接管理,事务管理,配置加载和缓存处理,这些共用的东西,将他们抽取出来作为最基础的组件,为上层的数据处理层提供最基础的支撑.
        //           通过xml的方式或Java注解的方式配置数据库连接,事务,缓存,mapper文件等,并将这些配置信息加载到Mybatis的configuration对象中,供数据处理层使用





        //将XML配置文件构建为Configuration配置类
        //通过加载配置文件流构建一个SqlSessionFactory,具体是一个DefaultSqlSessionFactory
        //总共分三步:
        //1.利用XMLConfigBuilder解析除<mapper>以外的标签(全局配置文件)
        //2.利用XMLMapperBuilder解析<mapper>标签(全局配置文件)
        //3.利用XMLScriptBuilder解析<select|update|insert|delete>标签(每一个mapper文件)
        //解析完成后,将解析的内容放入Configuration对象中,此时,基础支撑层创建完毕,用于给稍后的sql执行,解析等操作提供支撑
        //进入build()方法跟踪注释
        SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(Resources.getResourceAsStream("mybatis.xml"));

        //创建SqlSession:SqlSession是程序员和mybatis交流的桥梁,SqlSession提供了很多API(增删改查,提交事务,关闭事务,回滚事务等),方便程序员去调用来操作数据库(其中使用到了门面模式)
        //真正做事的是内部创建的Executor(改,查,提交/关闭执行器,维护缓存)
        //Executor的具体实现Mybatis提供了三种:
        //1.SimpleExecutor(默认):简单执行器,每次执行sql都会创建一个新的预处理器-PrepareStatement
        //2.ReuseExecutor:可重用执行器,相同的sql只生成一次预处理器,后续会重用此预处理器
        //3.BatchExecutor:批处理执行器,拥有ReuseExecutor的特性的同时,会利用jdbc的addBatch/executeBatch进行批处理

        //mybatis的缓存分为2类，分别是一级缓存和二级缓存
        //一级缓存:
        //一级缓存是默认开启的，它在一个sqlSession会话里面的所有查询操作都会保存到缓存中，所以，当再次执行参数相同的相同查询时，就不需要实际查询数据库了
        //由于缓存会被用来解决循环引用问题和加快重复嵌套查询的速度，所以无法将一级缓存完全禁用
        //一般来说一个请求中的所有增删改查操作都是在同一个sqlSession里面的，所以我们可以认为每个请求都有自己的一级缓存，
        //如果同一个sqlSession会话中2个查询中间有一个 insert 、update或delete 语句，那么之前查询的所有缓存都会清空,
        //因为每次增删改操作都有可能会改变原来的数据，所以必须刷新缓存.
        //
        //二级缓存:
        //二级缓存是全局的，也就是说；多个请求可以共用一个缓存，
        //缓存会先放在一级缓存中，当sqlSession会话提交或者关闭时才会将一级缓存刷新到二级缓存中；
        //开启二级缓存后，用户查询时，会先去二级缓存中找，找不到在去一级缓存中找；
        //二级缓存需要手动开启(默认是关闭的)，有2种方式配置二级缓存
        //1.单个mapper配置，将需要开启二级缓存的mapper.xml文件中加上<cache/>标签即可开启
        //2.所有mapper配置,在mybatis.xml中加入以下配置即可
        //<settings>
        //    <!--  开启所有mapper的二级缓存 -->
        //    <setting name="cacheEnabled" value="true" />
        //</settings>

        //对应的缓存实现逻辑:
        //mybatis还有一个BaseExecutor,是上面三个类的父类,在BaseExecutor中,实现了一级缓存的逻辑
        //mybatis还有一个CachingExecutor,内部有一个Executor属性,一旦开启了二级缓存,则将BaseExecutor赋值给内部的Executor属性(相当于给BaseExecutor包装了一层),在CachingExecutor当中完成二级缓存的逻辑,而一级缓存的逻辑则通过委托的方式,委托给内部的Executor属性(装饰器模式)
        //而对SQL进行解析就在最外层的Executor中进行(只有一级缓存则最外层就是BaseExecutor,开启二级缓存则最外层就是CachingExecutor)

        //随后利用StatementHandler获取数据库连接,然后通过ParameterHandler处理参数(参数映射配置,参数映射解析,参数类型解析),执行SQL
        //将获得的结果利用ResultSetHandler进行处理(结果映射配置,结果类型转换,结果数据拷贝).
        //上述的操作都是在SimpleExecutor或ReuseExecutor或BatchExecutor中执行的
        //全部执行完毕后,mybatis核心逻辑结束

        //进入openSession()方法跟踪注释
        //1.创建事务
        //2.创建Executor执行器(解析插件并利用JDK动态代理生成代理对象)
        //3.封装成DefaultSqlSession返回
        SqlSession sqlSession = sqlSessionFactory.openSession();

        //直接执行SQL
        //进入selectOne()方法跟踪注释
        //1.从二级缓存中拿(如果开启了二级缓存,逻辑在CachingExecutor中)
        //2.从一级缓存中拿(逻辑在BaseExecutor中)
        //3.一级和二级都没有,去数据库获取(逻辑在Batch或Reuse或Simple执行器中)
        String selectByXML = sqlSession.selectOne("com.jianghaotian.mybatis.mapper.UserMapper.selectByXML",null);
        System.out.println(selectByXML);

        //此处的UserMapper是mybatis生成的UserMapper代理对象
        //UserMapper mapper = sqlSession.getMapper(UserMapper.class);
        //String selectByAnnotation = mapper.selectByAnnotation();
        //System.out.println(selectByAnnotation);

        //提交操作
        //进入commit()方法跟踪注释
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
