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
package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class DynamicSqlSource implements SqlSource {

    private final Configuration configuration;
    private final SqlNode rootSqlNode;

    public DynamicSqlSource(Configuration configuration, SqlNode rootSqlNode) {
        this.configuration = configuration;
        this.rootSqlNode = rootSqlNode;
    }

    @Override
    public BoundSql getBoundSql(Object parameterObject) {
        DynamicContext context = new DynamicContext(configuration, parameterObject);
        //1.责任链 处理一个个SqlNode 编译出一个完整SQL
        //rootSqlNode即最外层的sqlNode,其内部包含一个List<SqlNode>,循环调用每一个sqlNode的apply()方法
        rootSqlNode.apply(context);

        SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);

        //2.接下来处理sql中的#{},替换成?,并把#{}的内容封装成一个parameterMapping,然后放到SqlSource里(在里面可以看到mybatis用了什么typeHandler)
        Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
        //这个SqlSource具体类型是一个StaticSqlSource
        SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings());

        //将sql,parameterMapping,参数值封装成一个BoundSql
        BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
        context.getBindings().forEach(boundSql::setAdditionalParameter);
        return boundSql;
    }

}
