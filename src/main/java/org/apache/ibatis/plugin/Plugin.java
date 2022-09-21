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
package org.apache.ibatis.plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.util.MapUtil;

/**
 * @author Clinton Begin
 */
public class Plugin implements InvocationHandler {

    private final Object target;
    private final Interceptor interceptor;
    private final Map<Class<?>, Set<Method>> signatureMap;

    private Plugin(Object target, Interceptor interceptor, Map<Class<?>, Set<Method>> signatureMap) {
        this.target = target;
        this.interceptor = interceptor;
        this.signatureMap = signatureMap;
    }

    public static Object wrap(Object target, Interceptor interceptor) {
        //解析@Intercepts注解(一个插件类可能会有多个@Intercepts),并生成一个map key-拦截的类 value-对应拦截类的具体拦截方法集合
        Map<Class<?>, Set<Method>> signatureMap = getSignatureMap(interceptor);
        //获取target的Class对象(target是Executor,StatementHandler,ResultSetHandler,ParameterHandler其中一个)
        Class<?> type = target.getClass();
        //从Class对象里找到signatureMap定义的拦截方法
        Class<?>[] interfaces = getAllInterfaces(type, signatureMap);
        //如果找到了
        if (interfaces.length > 0) {
            //用JDK动态代理,生成代理对象(代理拦截的方法)
            return Proxy.newProxyInstance(
                    type.getClassLoader(),
                    interfaces,
                    new Plugin(target, interceptor, signatureMap));
        }
        return target;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            //获取拦截的方法集合
            Set<Method> methods = signatureMap.get(method.getDeclaringClass());
            //如果当前方法在拦截方法集合里
            if (methods != null && methods.contains(method)) {
                //直接拦截链
                return interceptor.intercept(new Invocation(target, method, args));
            }
            //调用真正的方法
            return method.invoke(target, args);
        } catch (Exception e) {
            throw ExceptionUtil.unwrapThrowable(e);
        }
    }

    private static Map<Class<?>, Set<Method>> getSignatureMap(Interceptor interceptor) {
        //获取插件类上的@Intercepts注解
        Intercepts interceptsAnnotation = interceptor.getClass().getAnnotation(Intercepts.class);
        // issue #251
        //如果没有,抛异常
        if (interceptsAnnotation == null) {
            throw new PluginException("No @Intercepts annotation was found in interceptor " + interceptor.getClass().getName());
        }
        //@Intercepts注解的@Signature属性(是个数组)
        Signature[] sigs = interceptsAnnotation.value();
        //返回map
        Map<Class<?>, Set<Method>> signatureMap = new HashMap<>();
        //循环每一个@Signature
        for (Signature sig : sigs) {
            //根据type属性,拿到方法集合
            Set<Method> methods = MapUtil.computeIfAbsent(signatureMap, sig.type(), k -> new HashSet<>());
            try {
                //获取method属性,并生成一个Method对象
                Method method = sig.type().getMethod(sig.method(), sig.args());
                //将Method对象加入到集合中
                methods.add(method);
            } catch (NoSuchMethodException e) {
                throw new PluginException("Could not find method on " + sig.type() + " named " + sig.method() + ". Cause: " + e, e);
            }
        }
        return signatureMap;
    }

    private static Class<?>[] getAllInterfaces(Class<?> type, Map<Class<?>, Set<Method>> signatureMap) {
        Set<Class<?>> interfaces = new HashSet<>();
        while (type != null) {
            //循环Class对象的所有接口
            for (Class<?> c : type.getInterfaces()) {
                //如果当前接口被插件拦截了
                if (signatureMap.containsKey(c)) {
                    //加入到返回集合中
                    interfaces.add(c);
                }
            }
            //再从父类找
            type = type.getSuperclass();
        }
        return interfaces.toArray(new Class<?>[0]);
    }

}
