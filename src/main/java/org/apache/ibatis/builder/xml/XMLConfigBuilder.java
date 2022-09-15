/*
 *    Copyright 2009-2022 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

    private boolean parsed;
    private final XPathParser parser;
    private String environment;
    private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

    public XMLConfigBuilder(Reader reader) {
        this(reader, null, null);
    }

    public XMLConfigBuilder(Reader reader, String environment) {
        this(reader, environment, null);
    }

    public XMLConfigBuilder(Reader reader, String environment, Properties props) {
        this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    public XMLConfigBuilder(InputStream inputStream) {
        this(inputStream, null, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment) {
        this(inputStream, environment, null);
    }

    public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
        //先构造一个XPathParser,这个是真正解析xml文件标签的解析器
        //再调用构造方法
        this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
    }

    private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
        //new了一个Configuration对象,其中就会默认注册许多别名,别名的作用就是替代类的全限定性名,方便书写
        super(new Configuration());
        ErrorContext.instance().resource("SQL Mapper Configuration");
        this.configuration.setVariables(props);
        this.parsed = false;
        this.environment = environment;
        this.parser = parser;
    }

    public Configuration parse() {
        //全局xml配置文件只需要解析一次,不需要重复解析
        //如果已经解析,则抛异常
        if (parsed) {
            throw new BuilderException("Each XMLConfigBuilder can only be used once.");
        }


        //如果没有解析,则设置解析标志=true
        parsed = true;
        //从configuration标签开始解析
        //parser.evalNode("/configuration")返回的是整个configuration标签内的所有内容
        //例如:
        //<configuration>
        //
        //     .......
        //
        //</configuration>
        //解析xml文件的时候会经常遇到evalNode()方法,所得到的都是标签内的所有内容
        parseConfiguration(parser.evalNode("/configuration"));
        return configuration;
    }

    private void parseConfiguration(XNode root) {
        try {
            // issue #117 read properties first
            //解析<properties>标签:属性文件
            propertiesElement(root.evalNode("properties"));

            //解析<settings>标签:全局配置
            Properties settings = settingsAsProperties(root.evalNode("settings"));
            //VFS:虚拟文件系统(基本没用过该属性)
            //可以读取本地的文件或者FTP文件系统的文件资源
            loadCustomVfs(settings);
            //指定mybatis所用的日志的具体实现
            //以下是Mybatis默认实现的:
            //SLF4J
            //COMMONS_LOGGING
            //LOG4J,LOG4J2
            //JDK_LOGGING
            //STDOUT_LOGGING
            //NO_LOGGING
            loadCustomLogImpl(settings);

            //解析<typeAliases>标签:别名
            typeAliasesElement(root.evalNode("typeAliases"));

            //解析<plugins>标签:插件
            pluginElement(root.evalNode("plugins"));

            //解析<objectFactory>标签:用于反射实例化对象的工厂(一般不会去配置)
            objectFactoryElement(root.evalNode("objectFactory"));

            //解析<objectWrapperFactory>标签:创建对象的包装工厂(一般不会去配置)
            objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));

            //解析<reflectorFactory>标签:用于反射调用setter/getter方法或者反射获取属性的工厂(一般不会去配置)
            reflectorFactoryElement(root.evalNode("reflectorFactory"));

            //设置全局配置的默认值
            settingsElement(settings);
            // read it after objectFactory and objectWrapperFactory issue #631
            //解析<environments>标签:数据库环境
            environmentsElement(root.evalNode("environments"));

            //解析<databaseIdProvider>标签:数据库厂商标识
            databaseIdProviderElement(root.evalNode("databaseIdProvider"));

            //解析<typeHandlers>标签:类型处理器
            typeHandlerElement(root.evalNode("typeHandlers"));

            //解析<mappers>标签:mapper文件
            mapperElement(root.evalNode("mappers"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
        }
    }

    private Properties settingsAsProperties(XNode context) {
        //<settings>标签本质上是mybatis的一些全部配置
        //例如:
        //<settings>
        //   开启下划线命名转驼峰
        //   <setting name="mapUnderscoreToCamelCase" value="true"/>
        //</settings>
        if (context == null) {
            return new Properties();
        }

        //mybatis所有的全局属性都在Configuration类中定义
        //protected boolean safeRowBoundsEnabled;
        //    protected boolean safeResultHandlerEnabled = true;
        //    protected boolean mapUnderscoreToCamelCase;
        //    protected boolean aggressiveLazyLoading;
        //    protected boolean multipleResultSetsEnabled = true;
        //    protected boolean useGeneratedKeys;
        //    protected boolean useColumnLabel = true;
        //    protected boolean cacheEnabled = true;
        //    protected boolean callSettersOnNulls;
        //    protected boolean useActualParamName = true;
        //    protected boolean returnInstanceForEmptyRow;
        //    protected boolean shrinkWhitespacesInSql;
        //    protected boolean nullableOnForEach;
        //    protected boolean argNameBasedConstructorAutoMapping;
        //
        //    protected String logPrefix;
        //    protected Class<? extends Log> logImpl;
        //    protected Class<? extends VFS> vfsImpl;
        //    protected Class<?> defaultSqlProviderType;
        //    protected LocalCacheScope localCacheScope = LocalCacheScope.SESSION;
        //    protected JdbcType jdbcTypeForNull = JdbcType.OTHER;
        //    protected Set<String> lazyLoadTriggerMethods = new HashSet<>(Arrays.asList("equals", "clone", "hashCode", "toString"));
        //    protected Integer defaultStatementTimeout;
        //    protected Integer defaultFetchSize;
        //    protected ResultSetType defaultResultSetType;
        //    protected ExecutorType defaultExecutorType = ExecutorType.SIMPLE;
        //    protected AutoMappingBehavior autoMappingBehavior = AutoMappingBehavior.PARTIAL;
        //    protected AutoMappingUnknownColumnBehavior autoMappingUnknownColumnBehavior = AutoMappingUnknownColumnBehavior.NONE;


        //获取<setting>标签
        Properties props = context.getChildrenAsProperties();
        // Check that all settings are known to the configuration class
        //获取Configuration类的元数据
        MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
        //循环所有的<setting>标签
        for (Object key : props.keySet()) {
            //如果Configuration类里有setter()方法,那就表示mybatis存在这个全局设置,则通过,
            //反之,如果没有setter()方法,那就表示mybatis不存在这个全局设置,那就抛出异常
            if (!metaConfig.hasSetter(String.valueOf(key))) {
                throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
            }
        }
        return props;
    }

    private void loadCustomVfs(Properties props) throws ClassNotFoundException {
        String value = props.getProperty("vfsImpl");
        if (value != null) {
            String[] clazzes = value.split(",");
            for (String clazz : clazzes) {
                if (!clazz.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Class<? extends VFS> vfsImpl = (Class<? extends VFS>) Resources.classForName(clazz);
                    configuration.setVfsImpl(vfsImpl);
                }
            }
        }
    }

    private void loadCustomLogImpl(Properties props) {
        Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
        configuration.setLogImpl(logImpl);
    }

    private void typeAliasesElement(XNode parent) {
        //<typeAliases>是处理别名的,类的全限定性名太长,每次写都要写一大长串,不方便,这个时候就可以给这个全限定性名设置一个别名
        //设置好后,在用到这个全限定性名的时候,直接用别名替代就可以
        //有两个写法
        //1.
        //<typeAliases>
        //    <typeAlias type="com.jianghaotian.mybatis.mapper.UserMapper" alias="User"/>
        //</typeAliases>
        //2.
        //<typeAliases>
        //    <package name="com.jianghaotian.mybatis.mapper"/>
        //</typeAliases>
        //当然mybatis会默认帮我们注册一些别名,具体可见Configuration类的构造方法和TypeAliasRegistry的构造方法
        if (parent != null) {
            //循环<typeAliases>的子标签
            for (XNode child : parent.getChildren()) {
                //如果是一个<package>标签
                if ("package".equals(child.getName())) {
                    //获得name属性
                    String typeAliasPackage = child.getStringAttribute("name");
                    //扫描name属性对应的包下所有的java对象,在没有注解的情况下，会使用 Bean 的首字母小写的非限定类名来作为它的别名
                    //若有注解，则别名为其注解值
                    //例如:
                    //@Alias("author")
                    //public class Author {
                    //    ...
                    //}
                    configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
                } else {
                    //获取别名属性
                    String alias = child.getStringAttribute("alias");
                    //获取类属性
                    String type = child.getStringAttribute("type");
                    try {
                        //获得类的Class对象
                        Class<?> clazz = Resources.classForName(type);
                        //如果别名属性为空
                        if (alias == null) {
                            //先找这个类上的@Alias注解,如果有就用注解值作为别名
                            //如果没有@Alias注解,则使用首字母小写的非限定类名来作为它的别名
                            typeAliasRegistry.registerAlias(clazz);
                        } else {
                            //别名属性不为空,则直接使用此别名
                            typeAliasRegistry.registerAlias(alias, clazz);
                        }
                    } catch (ClassNotFoundException e) {
                        throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
                    }
                }
            }
        }
    }

    private void pluginElement(XNode parent) throws Exception {
        //<plugins>允许你在映射语句执行过程中的某一点进行拦截调用
        //mybatis提供了许多拦截点
        //Executor (update, query, flushStatements, commit, rollback, getTransaction, close, isClosed)
        //ParameterHandler (getParameterObject, setParameters)
        //ResultSetHandler (handleResultSets, handleOutputParameters)
        //StatementHandler (prepare, parameterize, batch, update, query)

        //声明方式为:
        //<plugins>
        //    <plugin interceptor="com.jianghaotian.mybatis.plugins.DemoPlugin">
        //        <property name="name" value="1234"/>
        //    </plugin>
        //</plugins>
        //程序员定义的插件必须实现Interceptor接口(mybatis包下的)
        //上面的插件将会拦截在 Executor 实例中所有的 “update” 方法调用， 这里的 Executor 是负责执行底层映射语句的内部对象。
        if (parent != null) {
            //循环所有的<plugin>标签
            for (XNode child : parent.getChildren()) {
                //获得interceptor属性
                String interceptor = child.getStringAttribute("interceptor");
                //获得<property>属性并封装为一个properties对象
                Properties properties = child.getChildrenAsProperties();
                //根据interceptor属性对应的类名,利用反射创建实例
                Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).getDeclaredConstructor().newInstance();
                //调用配置的插件的setProperties()方法(程序员要进行重写),并将properties对象传入进去
                interceptorInstance.setProperties(properties);
                //将插件放入到configuration对象中去(具体会设置到interceptorChain属性,一个拦截链,因为可以配置多个插件,所以拦截的时候就要按顺序依次执行拦截方法)
                configuration.addInterceptor(interceptorInstance);
            }
        }
    }

    private void objectFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            Properties properties = context.getChildrenAsProperties();
            ObjectFactory factory = (ObjectFactory) resolveClass(type).getDeclaredConstructor().newInstance();
            factory.setProperties(properties);
            configuration.setObjectFactory(factory);
        }
    }

    private void objectWrapperFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).getDeclaredConstructor().newInstance();
            configuration.setObjectWrapperFactory(factory);
        }
    }

    private void reflectorFactoryElement(XNode context) throws Exception {
        if (context != null) {
            String type = context.getStringAttribute("type");
            ReflectorFactory factory = (ReflectorFactory) resolveClass(type).getDeclaredConstructor().newInstance();
            configuration.setReflectorFactory(factory);
        }
    }

    private void propertiesElement(XNode context) throws Exception {
        //properties本质上是一个key-value格式的属性键值对
        //有三种配置方式(第1种和第2种不可同时存在)
        //1.<properties resource="db.properties"/>
        //2.<properties url="http://xxxx.xxxx.xxxx/db.properties"/>
        //3.<properties>
        //     <property name="username" value="123"/>
        //     <property name="password" value="123"/>
        //  </properties>
        if (context != null) {
            //先把第3种方式的<property>标签取出来,根据name和value的值,设置到Properties对象中去
            Properties defaults = context.getChildrenAsProperties();
            //是否有resource属性
            String resource = context.getStringAttribute("resource");
            //是否有url属性
            String url = context.getStringAttribute("url");
            //resource属性和url属性不可同时存在
            if (resource != null && url != null) {
                throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
            }
            if (resource != null) {
                //将resource属性的值对应的文件读出来,并放入Properties对象中
                defaults.putAll(Resources.getResourceAsProperties(resource));
            } else if (url != null) {
                //将url属性的值对应的文件读出来,并放入Properties对象中
                defaults.putAll(Resources.getUrlAsProperties(url));
            }
            //如果有手动传入的Properties(例如:build(Reader reader, Properties properties))
            Properties vars = configuration.getVariables();
            if (vars != null) {
                //则放入Properties对象中
                defaults.putAll(vars);
            }
            parser.setVariables(defaults);
            //放入到configuration对象中
            configuration.setVariables(defaults);
        }
    }

    private void settingsElement(Properties props) {
        configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
        configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
        configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
        configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
        configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
        configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
        configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
        configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
        configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
        configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
        configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
        configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
        configuration.setDefaultResultSetType(resolveResultSetType(props.getProperty("defaultResultSetType")));
        configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
        configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
        configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
        configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
        configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
        configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
        configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
        configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
        configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
        configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
        configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
        configuration.setLogPrefix(props.getProperty("logPrefix"));
        configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
        configuration.setShrinkWhitespacesInSql(booleanValueOf(props.getProperty("shrinkWhitespacesInSql"), false));
        configuration.setArgNameBasedConstructorAutoMapping(booleanValueOf(props.getProperty("argNameBasedConstructorAutoMapping"), false));
        configuration.setDefaultSqlProviderType(resolveClass(props.getProperty("defaultSqlProviderType")));
        configuration.setNullableOnForEach(booleanValueOf(props.getProperty("nullableOnForEach"), false));
    }

    private void environmentsElement(XNode context) throws Exception {
        //<environments>:数据库环境,一个应用可能对应多个数据库,比如dev,test,master都要对应一个数据库配置
        //尽管可以配置多个环境，但每个 SqlSessionFactory 实例只能选择一种环境。
        //所以，如果你想连接两个数据库，就需要创建两个 SqlSessionFactory 实例，每个数据库对应一个。而如果是三个数据库，就需要三个实例，依此类推
        //也就是每个数据库对应一个 SqlSessionFactory 实例

        //为了指定创建哪种环境，只要将它作为可选的参数传递给 SqlSessionFactoryBuilder 即可,可以接受环境配置的两个方法签名是：
        //SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(reader, environment);
        //SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(reader, environment, properties);

        //如果使用无环境参数的方法，那么将会加载默认环境
        //SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(reader);
        //SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(reader, properties);

        //配置示例:
        //
        //<!-- 环境集合,default属性表示默认使用的环境名称,这个名称一定要在子节点的id属性匹配到才可以 -->
        //<environments default="development">
        //  <!-- 环境,id属性表示这个环境的名称 -->
        //  <environment id="development">
        //    <!-- 事务管理器,type属性表示具体使用哪一个,mybatis自带了2个JDBC,MANAGED
        //      JDBC – 这个配置直接使用了 JDBC 的提交和回滚设施，它依赖从数据源获得的连接来管理事务作用域。
        //      MANAGED – 这个配置几乎没做什么。它从不提交或回滚一个连接，而是让容器来管理事务的整个生命周期（比如 JEE 应用服务器的上下文）。
        //                默认情况下它会关闭连接。然而一些容器并不希望连接被关闭，因此需要将 closeConnection 属性设置为 false 来阻止默认的关闭行为
        //                <property name="closeConnection" value="false"/>
        //      当然,可以实现了TransactionFactory接口和Transaction接口,就可以完全自定义 MyBatis 对事务的处理
        //    -->
        //    <transactionManager type="JDBC">
        //      <property name="..." value="..."/>
        //    </transactionManager>
        //    <!-- 数据源,type属性表示数据源类型,mybatis自带了3个UNPOOLED,POOLED,JNDI
        //      UNPOOLED– 这个数据源的实现会每次请求时打开和关闭连接。虽然有点慢，但对那些数据库连接可用性要求不高的简单应用程序来说，是一个很好的选择。 性能表现则依赖于使用的数据库，对某些数据库来说，使用连接池并不重要，这个配置就很适合这种情形。
        //      POOLED– 这种数据源的实现利用“池”的概念将 JDBC 连接对象组织起来，避免了创建新的连接实例时所必需的初始化和认证时间。 这种处理方式很流行，能使并发 Web 应用快速响应请求。
        //      JNDI – 这个数据源实现是为了能在如 EJB 或应用服务器这类容器中使用，容器可以集中或在外部配置数据源，然后放置一个 JNDI 上下文的数据源引用。
        //
        //      可以通过实现DataSourceFactory接口来自定义数据源的实现(例如c3p0和阿里巴巴的druid就是采用这个方式)
        //    -->
        //    <dataSource type="POOLED">
        //      <property name="driver" value="${driver}"/>
        //      <property name="url" value="${url}"/>
        //      <property name="username" value="${username}"/>
        //      <property name="password" value="${password}"/>
        //    </dataSource>
        //  </environment>
        //</environments>
        if (context != null) {
            //如果没有手动指定环境名称
            if (environment == null) {
                //则获取default属性的值作为默认环境
                environment = context.getStringAttribute("default");
            }
            //循环每一个<environment>
            for (XNode child : context.getChildren()) {
                //获得id属性
                String id = child.getStringAttribute("id");
                //如果id的值和默认环境的值相同,才会加载
                if (isSpecifiedEnvironment(id)) {
                    //<transactionManager>:设置事务管理器
                    TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
                    //<dataSource>:设置数据源
                    DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
                    DataSource dataSource = dsFactory.getDataSource();
                    //将事务管理器和数据源封装成为一个Environment.Builder
                    Environment.Builder environmentBuilder = new Environment.Builder(id)
                            .transactionFactory(txFactory)
                            .dataSource(dataSource);
                    //将Environment.Builder设置到configuration对象中去
                    configuration.setEnvironment(environmentBuilder.build());
                    break;
                }
            }
        }
    }

    private void databaseIdProviderElement(XNode context) throws Exception {
        //<DatabaseIdProvider>:数据库厂商标识
        //各大数据库厂商执行的SQL语句大同小异,但也会有一些差异,这些差异不兼容,所有Mybatis可以根据不同的数据库厂商执行不同的SQL语句

        //配置示例:
        //<!-- 数据库厂商,type属性是数据库厂商提供者,mybatis自带1个(DB_VENDOR)
        //     作用就是得到数据库厂商的标识,执行不同的SQL
        // -->
        //<databaseIdProvider type="DB_VENDOR">
        //  <!-- 给不同的数据库厂商起别名 -->
        //  <property name="SQL Server" value="sqlserver"/>
        //  <property name="MySQL" value="mysql"/>
        //</databaseIdProvider>

        //假如在使用mybatis的过程中,mapper文件有下列内容:(两条设置了databaseId值，一条并未设置值。而databaseId的值与上面property配置的value值要一样)
        //<select id="getEmpById" resultType="com.atguigu.mybatis.bean.Employee">
        //	select * from tbl_employee where id = #{id}
        //</select>
        //
        //<select id="getEmpById" resultType="com.atguigu.mybatis.bean.Employee" databaseId="mysql">
        //	select * from tbl_employee where id = #{id}
        //</select>
        //
        //<select id="getEmpById" resultType="com.atguigu.mybatis.bean.Employee" databaseId="oracle">
        //	select EMPLOYEE_ID id,LAST_NAME	lastName,EMAIL email from employees where EMPLOYEE_ID=#{id}
        //</select>
        //
        //databaseId值为mysql表示：当数据库连接为mysql数据库时，该语句才会被执行，如果此时项目使用的是oracle数据则该条语句不会被执行。
        //没有设置databaseId表示：无论当前连接的什么数据库，该条语句都能被使用。但是如上面这种情况，如果使用的mysql数据库则databaseId=mysql且id为getEmpById的语句可以执行，而同时id也是getEmpById且没指定的databaseId的语句也可以被执行。这时系统将执行指定更加精确的databaseId=mysql且id为getEmpById的语句
        DatabaseIdProvider databaseIdProvider = null;
        if (context != null) {
            String type = context.getStringAttribute("type");
            // awful patch to keep backward compatibility
            if ("VENDOR".equals(type)) {
                type = "DB_VENDOR";
            }
            Properties properties = context.getChildrenAsProperties();
            databaseIdProvider = (DatabaseIdProvider) resolveClass(type).getDeclaredConstructor().newInstance();
            databaseIdProvider.setProperties(properties);
        }
        Environment environment = configuration.getEnvironment();
        if (environment != null && databaseIdProvider != null) {
            String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
            configuration.setDatabaseId(databaseId);
        }
    }

    private TransactionFactory transactionManagerElement(XNode context) throws Exception {
        if (context != null) {
            //type属性:事务管理器类型
            String type = context.getStringAttribute("type");
            Properties props = context.getChildrenAsProperties();
            //反射获取实例
            TransactionFactory factory = (TransactionFactory) resolveClass(type).getDeclaredConstructor().newInstance();
            //设置事务管理器的properties属性
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a TransactionFactory.");
    }

    private DataSourceFactory dataSourceElement(XNode context) throws Exception {
        if (context != null) {
            //type属性:数据源类型
            String type = context.getStringAttribute("type");
            Properties props = context.getChildrenAsProperties();
            //反射获取实例
            DataSourceFactory factory = (DataSourceFactory) resolveClass(type).getDeclaredConstructor().newInstance();
            factory.setProperties(props);
            return factory;
        }
        throw new BuilderException("Environment declaration requires a DataSourceFactory.");
    }

    private void typeHandlerElement(XNode parent) {
        //<typeHandlers>:类型处理器
        //MyBatis 在设置预处理语句（PreparedStatement）中的参数或从结果集中取出一个值时，都会用类型处理器将获取到的值以合适的方式转换成 Java 类型。

        //使用方式:
        //<typeHandlers>
        //    <typeHandler handler="com.jianghaotian.mybatis.typeHandlers.DemoTypeHandler"/>
        //</typeHandlers>
        if (parent != null) {
            //遍历每一个<typeHandler>
            for (XNode child : parent.getChildren()) {
                //如果有package属性
                if ("package".equals(child.getName())) {
                    //则mybatis会自动的从包名下去找实现了TypeHandler接口的类作为类型处理器,并加入到typeHandlerRegistry中
                    String typeHandlerPackage = child.getStringAttribute("name");
                    typeHandlerRegistry.register(typeHandlerPackage);
                } else {
                    //获取javaType属性,例如String.class
                    String javaTypeName = child.getStringAttribute("javaType");
                    //获取jdbcType属性,例如:JdbcType.VARCHAR
                    String jdbcTypeName = child.getStringAttribute("jdbcType");
                    //获取handler属性,例如com.xxx.DemoTypeHandler(这个类必须实现TypeHandler接口)
                    String handlerTypeName = child.getStringAttribute("handler");
                    Class<?> javaTypeClass = resolveClass(javaTypeName);
                    JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
                    Class<?> typeHandlerClass = resolveClass(handlerTypeName);
                    //如果指定了java类型
                    if (javaTypeClass != null) {
                        if (jdbcType == null) {
                            //未指定jdbc类型,则会去找类上的@MappedJdbcTypes注解,如果找到了就获取注解值作为jdbc类型,如果没找到就认为jdbc类型为null
                            typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
                        } else {
                            //指定了jdbc类型,直接进行注册
                            typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
                        }
                    } else {
                        //如果没指定java类型,先去类上去找@MappedTypes注解,如果找到了,则使用注解值,没找到则认为java类型为null
                        typeHandlerRegistry.register(typeHandlerClass);
                    }
                }
            }
        }
    }

    private void mapperElement(XNode parent) throws Exception {
        //<mapper>:SQL映射的xml文件
        //在自动查找资源方面，Java 并没有提供一个很好的解决方案，所以最好的办法是直接告诉 MyBatis 到哪里去找映射文件。
        //可以使用相对于类路径的资源引用，或完全限定资源定位符（包括 file:/// 形式的 URL），或类名和包名等

        //mybatis提供了4种使用方式:
        //<!-- 使用相对于类路径的资源引用 -->
        //<mappers>
        //  <mapper resource="org/mybatis/builder/AuthorMapper.xml"/>
        //  <mapper resource="org/mybatis/builder/BlogMapper.xml"/>
        //  <mapper resource="org/mybatis/builder/PostMapper.xml"/>
        //</mappers>

        //<!-- 使用完全限定资源定位符（URL） 不推荐-->
        //<mappers>
        //  <mapper url="file:///var/mappers/AuthorMapper.xml"/>
        //  <mapper url="file:///var/mappers/BlogMapper.xml"/>
        //  <mapper url="file:///var/mappers/PostMapper.xml"/>
        //</mappers>

        //<!-- 使用映射器接口实现类的完全限定类名 必须保证接口名和xml文件的名相同,并且还必须在一个包内-->
        //<mappers>
        //  <mapper class="org.mybatis.builder.AuthorMapper"/>
        //  <mapper class="org.mybatis.builder.BlogMapper"/>
        //  <mapper class="org.mybatis.builder.PostMapper"/>
        //</mappers>

        //<!-- 将包内的映射器接口实现全部注册为映射器 必须保证接口名和xml文件的名相同,并且还必须在一个包内-->
        //<mappers>
        //  <package name="org.mybatis.builder"/>
        //</mappers>
        if (parent != null) {
            //循环每一个<mapper>
            for (XNode child : parent.getChildren()) {
                //如果子标签是package
                if ("package".equals(child.getName())) {
                    //获取name属性作为包名
                    String mapperPackage = child.getStringAttribute("name");
                    //注册包内所有的接口都作为mapper添加到configuration对象中
                    configuration.addMappers(mapperPackage);
                } else {
                    //获取resource子属性的值
                    String resource = child.getStringAttribute("resource");
                    //获取url子属性的值
                    String url = child.getStringAttribute("url");
                    //获取class子属性的值
                    String mapperClass = child.getStringAttribute("class");
                    if (resource != null && url == null && mapperClass == null) {
                        //处理只有resource的情况
                        ErrorContext.instance().resource(resource);
                        try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
                            //创建XMLMapperBuilder
                            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
                            //解析mapper.xml文件
                            mapperParser.parse();
                        }
                    } else if (resource == null && url != null && mapperClass == null) {
                        //处理只有url的情况
                        ErrorContext.instance().resource(url);
                        try (InputStream inputStream = Resources.getUrlAsStream(url)) {
                            //创建XMLMapperBuilder
                            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
                            //解析mapper.xml文件
                            mapperParser.parse();
                        }
                    } else if (resource == null && url == null && mapperClass != null) {
                        //处理只有class的情况

                        //获取Class对象
                        Class<?> mapperInterface = Resources.classForName(mapperClass);
                        //如果是一个接口,直接添加到configuration对象中
                        configuration.addMapper(mapperInterface);
                    } else {
                        //其余情况抛异常
                        throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
                    }
                }
            }
        }
    }

    private boolean isSpecifiedEnvironment(String id) {
        if (environment == null) {
            throw new BuilderException("No environment specified.");
        }
        if (id == null) {
            throw new BuilderException("Environment requires an id attribute.");
        }
        return environment.equals(id);
    }

}
