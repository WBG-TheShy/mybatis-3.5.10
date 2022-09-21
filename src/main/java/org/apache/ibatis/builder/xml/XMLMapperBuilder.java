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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLMapperBuilder extends BaseBuilder {

    private final XPathParser parser;
    private final MapperBuilderAssistant builderAssistant;
    private final Map<String, XNode> sqlFragments;
    private final String resource;

    @Deprecated
    public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
        this(reader, configuration, resource, sqlFragments);
        this.builderAssistant.setCurrentNamespace(namespace);
    }

    @Deprecated
    public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
                configuration, resource, sqlFragments);
    }

    public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
        this(inputStream, configuration, resource, sqlFragments);
        this.builderAssistant.setCurrentNamespace(namespace);
    }

    public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
                configuration, resource, sqlFragments);
    }

    private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        super(configuration);
        this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
        this.parser = parser;
        this.sqlFragments = sqlFragments;
        this.resource = resource;
    }

    public void parse() {
        //先去loadedResources集合中去看是否存在,如果不存在,表示没有解析过这个文件,才进行解析
        if (!configuration.isResourceLoaded(resource)) {
            //解析<mapper>标签
            configurationElement(parser.evalNode("/mapper"));
            //添加到loadedResources集合中去,表示已解析过
            configuration.addLoadedResource(resource);
            //将mapper标签的namespace属性(命名空间)的值,作为mapper接口,添加到configuration中去(如果还没解析的话才添加,如果已经解析了就啥也不干)
            bindMapperForNamespace();
        }

        parsePendingResultMaps();
        parsePendingCacheRefs();
        parsePendingStatements();
    }

    public XNode getSqlFragment(String refid) {
        return sqlFragments.get(refid);
    }

    private void configurationElement(XNode context) {
        try {
            //获取<mapper>标签的namespace属性:命名空间
            String namespace = context.getStringAttribute("namespace");
            if (namespace == null || namespace.isEmpty()) {
                throw new BuilderException("Mapper's namespace cannot be empty");
            }
            //builderAssistant:创建助手,它可以将xml文件中的每一个select|insert|update|delete节点都封装成一个MappedStatement对象,放入到configuration中

            //将namespace存入builderAssistant中
            builderAssistant.setCurrentNamespace(namespace);

            //解析<cache-ref>标签
            //对某一命名空间的语句，只会使用该命名空间的缓存进行缓存或刷新。
            //但你可能会想要在多个命名空间中共享相同的缓存配置和实例。要实现这种需求，你可以使用 cache-ref 元素来引用另一个缓存
            //配置方式:<cache-ref namespace="com.xxx.UserMapper"/>
            cacheRefElement(context.evalNode("cache-ref"));

            //解析<cache>标签:当前的xml文件使用二级缓存
            //使用方式非常简单:在xml文件中添加一行<cache/>即可
            //效果如下:
            //1.映射语句文件中的所有 select 语句的结果将会被缓存。
            //2.映射语句文件中的所有 insert、update 和 delete 语句会刷新缓存。
            //3.缓存会使用最近最少使用算法（LRU, Least Recently Used）算法来清除不需要的缓存。
            //4.缓存不会定时进行刷新（也就是说，没有刷新间隔）。
            //5.缓存会保存列表或对象（无论查询方法返回哪种）的 1024 个引用。
            //6.缓存会被视为读/写缓存，这意味着获取到的对象并不是共享的，可以安全地被调用者修改，而不干扰其他调用者或线程所做的潜在修改。
            //最终会set到configuration的caches中
            cacheElement(context.evalNode("cache"));

            //解析<parameterMap>节点(该节点在mybatis3.5版本已经不推荐使用了)
            parameterMapElement(context.evalNodes("/mapper/parameterMap"));

            //解析<resultMap>节点:结果映射
            //最终解析到configuration的resultMap中
            resultMapElements(context.evalNodes("/mapper/resultMap"));

            //解析<sql>节点:用来定义可重用的 SQL 代码片段，以便在其它语句中使用。
            //参数可以静态地（在加载的时候）确定下来，并且可以在不同的 include 元素中定义不同的参数值。
            sqlElement(context.evalNodes("/mapper/sql"));

            //解析<select|insert|update|delete>节点
            //最终将每一个节点解析成MappedStatement,set到configuration中的mappedStatement中
            buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
        }
    }

    private void buildStatementFromContext(List<XNode> list) {
        if (configuration.getDatabaseId() != null) {
            buildStatementFromContext(list, configuration.getDatabaseId());
        }
        buildStatementFromContext(list, null);
    }

    private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
        //入参的list就是<select|insert|update|delete>节点的集合

        //遍历节点集合
        for (XNode context : list) {
            //创建一个Statement解析器
            final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
            try {
                //解析并封装成一个MappedStatement,添加到configuration中去
                statementParser.parseStatementNode();
            } catch (IncompleteElementException e) {
                configuration.addIncompleteStatement(statementParser);
            }
        }
    }

    private void parsePendingResultMaps() {
        Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
        synchronized (incompleteResultMaps) {
            Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().resolve();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // ResultMap is still missing a resource...
                }
            }
        }
    }

    private void parsePendingCacheRefs() {
        Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
        synchronized (incompleteCacheRefs) {
            Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().resolveCacheRef();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // Cache ref is still missing a resource...
                }
            }
        }
    }

    private void parsePendingStatements() {
        Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
        synchronized (incompleteStatements) {
            Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().parseStatementNode();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // Statement is still missing a resource...
                }
            }
        }
    }

    private void cacheRefElement(XNode context) {
        if (context != null) {
            configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
            CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
            try {
                cacheRefResolver.resolveCacheRef();
            } catch (IncompleteElementException e) {
                configuration.addIncompleteCacheRef(cacheRefResolver);
            }
        }
    }

    private void cacheElement(XNode context) {
        if (context != null) {
            //解析<cache>标签的type属性:缓存实现类,如果未设置,默认是PERPETUAL(具体实现为PerpetualCache,这个类实现了永久缓存,也就是没设置过期时间)
            String type = context.getStringAttribute("type", "PERPETUAL");
            //加载缓存实现类
            Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);

            //获取<cache>标签的eviction属性:缓存数量满后的淘汰机制,如果未设置,则默认使用LRU(具体实现为LruCache)
            //mybatis提供了4种淘汰机制
            //LRU – 最近最少使用：移除最长时间不被使用的对象。(LruCache)-使用LinkedHashMap实现
            //FIFO – 先进先出：按对象进入缓存的顺序来移除它们。(FifoCache)-使用LinkedList实现
            //SOFT – 软引用：基于垃圾回收器状态和软引用规则移除对象。(SoftCache)
            //WEAK – 弱引用：更积极地基于垃圾收集器状态和弱引用规则移除对象。(WeakCache)
            String eviction = context.getStringAttribute("eviction", "LRU");

            //加载淘汰机制类
            Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);

            //获取<cache>标签的flushInterval属性:刷新间隔,单位毫秒,如果未设置,也就是没有刷新间隔，缓存仅仅会在调用语句时刷新
            //如果设置为flushInterval = 60000,表示mybatis每60秒刷新一次缓存
            Long flushInterval = context.getLongAttribute("flushInterval");

            //获取<cache>标签的size属性:引用数目,也就是缓存的数量,任意正整数,默认为1024
            Integer size = context.getIntAttribute("size");

            //获取<cache>标签的readOnly属性:只读属性可以被设置为 true 或 false。只读的缓存会给所有调用者返回缓存对象的相同实例。
            //因此这些对象不能被修改。这就提供了可观的性能提升。而可读写的缓存会（通过序列化）返回缓存对象的拷贝。 速度上会慢一些，但是更安全，因此默认值是 false。
            boolean readWrite = !context.getBooleanAttribute("readOnly", false);
            //获取<cache>标签的blocking属性:true或者false,如果为true则会使用BlockingCache
            boolean blocking = context.getBooleanAttribute("blocking", false);
            Properties props = context.getChildrenAsProperties();
            //将参数封装成一个Cache对象(包装成一个大Cache,将最底层的Cache一层一层的用其他Cache包装,类似穿衣服),并放入到configuration对象中
            builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
        }
    }

    private void parameterMapElement(List<XNode> list) {
        for (XNode parameterMapNode : list) {
            String id = parameterMapNode.getStringAttribute("id");
            String type = parameterMapNode.getStringAttribute("type");
            Class<?> parameterClass = resolveClass(type);
            List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
            List<ParameterMapping> parameterMappings = new ArrayList<>();
            for (XNode parameterNode : parameterNodes) {
                String property = parameterNode.getStringAttribute("property");
                String javaType = parameterNode.getStringAttribute("javaType");
                String jdbcType = parameterNode.getStringAttribute("jdbcType");
                String resultMap = parameterNode.getStringAttribute("resultMap");
                String mode = parameterNode.getStringAttribute("mode");
                String typeHandler = parameterNode.getStringAttribute("typeHandler");
                Integer numericScale = parameterNode.getIntAttribute("numericScale");
                ParameterMode modeEnum = resolveParameterMode(mode);
                Class<?> javaTypeClass = resolveClass(javaType);
                JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
                Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
                ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
                parameterMappings.add(parameterMapping);
            }
            builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
        }
    }

    private void resultMapElements(List<XNode> list) {
        for (XNode resultMapNode : list) {
            try {
                resultMapElement(resultMapNode);
            } catch (IncompleteElementException e) {
                // ignore, it will be retried
            }
        }
    }

    private ResultMap resultMapElement(XNode resultMapNode) {
        return resultMapElement(resultMapNode, Collections.emptyList(), null);
    }

    private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings, Class<?> enclosingType) {
        ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
        String type = resultMapNode.getStringAttribute("type",
                resultMapNode.getStringAttribute("ofType",
                        resultMapNode.getStringAttribute("resultType",
                                resultMapNode.getStringAttribute("javaType"))));
        Class<?> typeClass = resolveClass(type);
        if (typeClass == null) {
            typeClass = inheritEnclosingType(resultMapNode, enclosingType);
        }
        Discriminator discriminator = null;
        List<ResultMapping> resultMappings = new ArrayList<>(additionalResultMappings);
        List<XNode> resultChildren = resultMapNode.getChildren();
        for (XNode resultChild : resultChildren) {
            if ("constructor".equals(resultChild.getName())) {
                processConstructorElement(resultChild, typeClass, resultMappings);
            } else if ("discriminator".equals(resultChild.getName())) {
                discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
            } else {
                List<ResultFlag> flags = new ArrayList<>();
                if ("id".equals(resultChild.getName())) {
                    flags.add(ResultFlag.ID);
                }
                resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
            }
        }
        String id = resultMapNode.getStringAttribute("id",
                resultMapNode.getValueBasedIdentifier());
        String extend = resultMapNode.getStringAttribute("extends");
        Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
        ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
        try {
            return resultMapResolver.resolve();
        } catch (IncompleteElementException e) {
            configuration.addIncompleteResultMap(resultMapResolver);
            throw e;
        }
    }

    protected Class<?> inheritEnclosingType(XNode resultMapNode, Class<?> enclosingType) {
        if ("association".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
            String property = resultMapNode.getStringAttribute("property");
            if (property != null && enclosingType != null) {
                MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
                return metaResultType.getSetterType(property);
            }
        } else if ("case".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
            return enclosingType;
        }
        return null;
    }

    private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) {
        List<XNode> argChildren = resultChild.getChildren();
        for (XNode argChild : argChildren) {
            List<ResultFlag> flags = new ArrayList<>();
            flags.add(ResultFlag.CONSTRUCTOR);
            if ("idArg".equals(argChild.getName())) {
                flags.add(ResultFlag.ID);
            }
            resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
        }
    }

    private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) {
        String column = context.getStringAttribute("column");
        String javaType = context.getStringAttribute("javaType");
        String jdbcType = context.getStringAttribute("jdbcType");
        String typeHandler = context.getStringAttribute("typeHandler");
        Class<?> javaTypeClass = resolveClass(javaType);
        Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        Map<String, String> discriminatorMap = new HashMap<>();
        for (XNode caseChild : context.getChildren()) {
            String value = caseChild.getStringAttribute("value");
            String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings, resultType));
            discriminatorMap.put(value, resultMap);
        }
        return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
    }

    private void sqlElement(List<XNode> list) {
        if (configuration.getDatabaseId() != null) {
            sqlElement(list, configuration.getDatabaseId());
        }
        sqlElement(list, null);
    }

    private void sqlElement(List<XNode> list, String requiredDatabaseId) {
        for (XNode context : list) {
            String databaseId = context.getStringAttribute("databaseId");
            String id = context.getStringAttribute("id");
            id = builderAssistant.applyCurrentNamespace(id, false);
            if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
                sqlFragments.put(id, context);
            }
        }
    }

    private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
        if (requiredDatabaseId != null) {
            return requiredDatabaseId.equals(databaseId);
        }
        if (databaseId != null) {
            return false;
        }
        if (!this.sqlFragments.containsKey(id)) {
            return true;
        }
        // skip this fragment if there is a previous one with a not null databaseId
        XNode context = this.sqlFragments.get(id);
        return context.getStringAttribute("databaseId") == null;
    }

    private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) {
        String property;
        if (flags.contains(ResultFlag.CONSTRUCTOR)) {
            property = context.getStringAttribute("name");
        } else {
            property = context.getStringAttribute("property");
        }
        String column = context.getStringAttribute("column");
        String javaType = context.getStringAttribute("javaType");
        String jdbcType = context.getStringAttribute("jdbcType");
        String nestedSelect = context.getStringAttribute("select");
        String nestedResultMap = context.getStringAttribute("resultMap", () ->
                processNestedResultMappings(context, Collections.emptyList(), resultType));
        String notNullColumn = context.getStringAttribute("notNullColumn");
        String columnPrefix = context.getStringAttribute("columnPrefix");
        String typeHandler = context.getStringAttribute("typeHandler");
        String resultSet = context.getStringAttribute("resultSet");
        String foreignColumn = context.getStringAttribute("foreignColumn");
        boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
        Class<?> javaTypeClass = resolveClass(javaType);
        Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
    }

    private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings, Class<?> enclosingType) {
        if (Arrays.asList("association", "collection", "case").contains(context.getName())
                && context.getStringAttribute("select") == null) {
            validateCollection(context, enclosingType);
            ResultMap resultMap = resultMapElement(context, resultMappings, enclosingType);
            return resultMap.getId();
        }
        return null;
    }

    protected void validateCollection(XNode context, Class<?> enclosingType) {
        if ("collection".equals(context.getName()) && context.getStringAttribute("resultMap") == null
                && context.getStringAttribute("javaType") == null) {
            MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
            String property = context.getStringAttribute("property");
            if (!metaResultType.hasSetter(property)) {
                throw new BuilderException(
                        "Ambiguous collection type for property '" + property + "'. You must specify 'javaType' or 'resultMap'.");
            }
        }
    }

    private void bindMapperForNamespace() {
        String namespace = builderAssistant.getCurrentNamespace();
        if (namespace != null) {
            Class<?> boundType = null;
            try {
                boundType = Resources.classForName(namespace);
            } catch (ClassNotFoundException e) {
                // ignore, bound type is not required
            }
            if (boundType != null && !configuration.hasMapper(boundType)) {
                // Spring may not know the real resource name so we set a flag
                // to prevent loading again this resource from the mapper interface
                // look at MapperAnnotationBuilder#loadXmlResource
                configuration.addLoadedResource("namespace:" + namespace);
                configuration.addMapper(boundType);
            }
        }
    }

}
