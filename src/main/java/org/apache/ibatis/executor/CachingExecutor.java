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
package org.apache.ibatis.executor;

import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.TransactionalCacheManager;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class CachingExecutor implements Executor {

    private final Executor delegate;
    private final TransactionalCacheManager tcm = new TransactionalCacheManager();

    public CachingExecutor(Executor delegate) {
        this.delegate = delegate;
        delegate.setExecutorWrapper(this);
    }

    @Override
    public Transaction getTransaction() {
        return delegate.getTransaction();
    }

    @Override
    public void close(boolean forceRollback) {
        try {
            // issues #499, #524 and #573
            if (forceRollback) {
                tcm.rollback();
            } else {
                tcm.commit();
            }
        } finally {
            delegate.close(forceRollback);
        }
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public int update(MappedStatement ms, Object parameterObject) throws SQLException {
        flushCacheIfRequired(ms);
        return delegate.update(ms, parameterObject);
    }

    @Override
    public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
        flushCacheIfRequired(ms);
        return delegate.queryCursor(ms, parameter, rowBounds);
    }

    @Override
    public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
        //通过参数对象解析SQL
        //将MappedStatement里的所有sqlNode调用apply()方法,然后解析传入的参数并生成parameterMapping,然后封装成一个BoundSql
        BoundSql boundSql = ms.getBoundSql(parameterObject);
        //获得缓存的key(根据sql,参数值,数据库环境生成一个唯一的key)
        CacheKey key = createCacheKey(ms, parameterObject, rowBounds, boundSql);
        //再次调用查询
        return query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
    }

    @Override
    public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql)
            throws SQLException {
        //是否在mapper.xml文件里配置了二级缓存<cache/>,如果配置了则返回具体实现类,如果没配置返回null
        //记住,此时的cache使用了装饰器模式(穿了很多层的衣服)
        Cache cache = ms.getCache();

        //配置了二级缓存,直接执行
        if (cache != null) {
            //判断标签的flushCache属性是否是true,如果是,则清空缓存
            //例如:
            // <select flushCache = "true">
            //    select * from user
            // </select>
            flushCacheIfRequired(ms);

            //判断标签的useCache属性是否是true,如果是,则执行缓存逻辑
            //例如:
            // <select useCache = "true">
            //    select * from user
            // </select>
            if (ms.isUseCache() && resultHandler == null) {
                ensureNoOutParams(ms, boundSql);

                //先去二级缓存中(实际上只会从事务缓存管理器获取)去根据key去拿结果
                //这个tcm是一个事务缓存管理器,所有的二级缓存,都先存到这个tcm中的transactionalCaches属性中(是一个Map),直到事务真正提交了,才会真正的放入到二级缓存中去(也是委托给真正的缓存实现类)
                //如果事务回滚了,则直接清空(也是使用了委托)
                @SuppressWarnings("unchecked")
                List<E> list = (List<E>) tcm.getObject(cache, key);

                //二级缓存没有拿到
                if (list == null) {
                    //委托给BaseExecutor执行查询
                    list = delegate.query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
                    //将结果放入二级缓存中
                    tcm.putObject(cache, key, list); // issue #578 and #116
                }
                //返回结果
                return list;
            }
        }
        //没配置二级缓存的话
        //直接委托给BaseExecutor执行查询
        return delegate.query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
    }

    @Override
    public List<BatchResult> flushStatements() throws SQLException {
        return delegate.flushStatements();
    }

    @Override
    public void commit(boolean required) throws SQLException {
        //执行BaseExecutor的commit()
        delegate.commit(required);
        //事务缓存(二级缓存)的commit()
        tcm.commit();
    }

    @Override
    public void rollback(boolean required) throws SQLException {
        try {
            delegate.rollback(required);
        } finally {
            if (required) {
                tcm.rollback();
            }
        }
    }

    private void ensureNoOutParams(MappedStatement ms, BoundSql boundSql) {
        if (ms.getStatementType() == StatementType.CALLABLE) {
            for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
                if (parameterMapping.getMode() != ParameterMode.IN) {
                    throw new ExecutorException("Caching stored procedures with OUT params is not supported.  Please configure useCache=false in " + ms.getId() + " statement.");
                }
            }
        }
    }

    @Override
    public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
        return delegate.createCacheKey(ms, parameterObject, rowBounds, boundSql);
    }

    @Override
    public boolean isCached(MappedStatement ms, CacheKey key) {
        return delegate.isCached(ms, key);
    }

    @Override
    public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
        delegate.deferLoad(ms, resultObject, property, key, targetType);
    }

    @Override
    public void clearLocalCache() {
        delegate.clearLocalCache();
    }

    private void flushCacheIfRequired(MappedStatement ms) {
        Cache cache = ms.getCache();
        if (cache != null && ms.isFlushCacheRequired()) {
            tcm.clear(cache);
        }
    }

    @Override
    public void setExecutorWrapper(Executor executor) {
        throw new UnsupportedOperationException("This method should not be called");
    }

}
