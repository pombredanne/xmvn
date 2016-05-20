/*-
 * Copyright (c) 2013-2015 Red Hat, Inc.
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
package org.fedoraproject.xmvn.tools.bisect;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationOutputHandler;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.InvocationResult;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;

@Named
@Singleton
public class DefaultBuildExecutor
    implements BuildExecutor
{
    private final Invoker invoker;

    public DefaultBuildExecutor()
    {
        this.invoker = new DefaultInvoker();
    }

    @Override
    public boolean executeBuild( InvocationRequest request, String logPath, boolean verbose )
        throws MavenInvocationException
    {
        try (PrintWriter log = new PrintWriter( logPath ))
        {
            InvocationOutputHandler outputHandler = new InvocationOutputHandler()
            {
                @Override
                public void consumeLine( String line )
                {
                    log.println( line );
                }
            };

            request.setOutputHandler( outputHandler );
            request.setErrorHandler( outputHandler );

            File mavenHome = new File( request.getProperties().get( "maven.home" ).toString() );
            invoker.setMavenHome( mavenHome );
            InvocationResult result = invoker.execute( request );

            return result.getExitCode() == 0;
        }
        catch ( FileNotFoundException e )
        {
            throw new RuntimeException( e );
        }
    }
}
