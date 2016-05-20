/*-
 * Copyright (c) 2014-2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fedoraproject.xmvn.locator;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

/**
 * @author Mikolaj Izdebski
 */
class ServiceInvocationHandler
    implements InvocationHandler
{
    private final ClassLoader classLoader;

    private final Object delegate;

    public ServiceInvocationHandler( ClassLoader classLoader, Object delegate )
    {
        this.classLoader = classLoader;
        this.delegate = delegate;
    }

    @Override
    public Object invoke( Object proxy, Method method, Object[] args )
        throws Throwable
    {
        ClassLoader savedThreadContextClassLoader = Thread.currentThread().getContextClassLoader();

        try
        {
            Thread.currentThread().setContextClassLoader( classLoader );

            return method.invoke( delegate, args );
        }
        catch ( ReflectiveOperationException e )
        {
            throw new RuntimeException( e );
        }
        finally
        {
            Thread.currentThread().setContextClassLoader( savedThreadContextClassLoader );
        }
    }
}
