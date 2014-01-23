/*-
 * Copyright (c) 2012-2014 Red Hat, Inc.
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
package org.fedoraproject.xmvn.tools.resolve;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.inject.Inject;
import javax.inject.Named;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.sisu.space.SpaceModule;
import org.eclipse.sisu.space.URLClassSpace;
import org.eclipse.sisu.wire.WireModule;
import org.fedoraproject.xmvn.resolver.ResolutionRequest;
import org.fedoraproject.xmvn.resolver.Resolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.DynamicParameter;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;

/**
 * Resolve artifacts given on command line.
 * <p>
 * Return 0 when all artifacts are successfully resolved, 1 on failure to resolve one or more artifacts and 2 when some
 * other error occurs. In the last case a stack trace is printed too.
 * 
 * @author Mikolaj Izdebski
 */
@Named
public class ResolverCli
{
    private final Logger logger = LoggerFactory.getLogger( ResolverCli.class );

    @Parameter
    public List<String> parameters = new LinkedList<>();

    @Parameter( names = { "-h", "--help" }, help = true, description = "Display usage information" )
    private boolean help;

    @Parameter( names = { "-X", "--debug" }, description = "Display debugging information" )
    public boolean debug = false;

    @Parameter( names = { "-c", "--classpath" }, description = "Use colon instead of new line to separate resolved artifacts" )
    public boolean classpath = false;

    @DynamicParameter( names = "-D", description = "Define system property" )
    public Map<String, String> defines = new TreeMap<>();

    private final Resolver resolver;

    @Inject
    public ResolverCli( Resolver resolver )
    {
        this.resolver = resolver;
    }

    private void parseArgs( String[] args )
    {
        try
        {
            JCommander jcomm = new JCommander( this, args );
            jcomm.setProgramName( "xmvn-resolve" );

            if ( help )
            {
                System.out.println( "xmvn-resolve: Resolve artifacts from system repository" );
                System.out.println();
                jcomm.usage();
                System.exit( 0 );
            }

            for ( String param : defines.keySet() )
                System.setProperty( param, defines.get( param ) );
        }
        catch ( ParameterException e )
        {
            System.err.println( e.getMessage() + ". Specify -h for usage." );
            System.exit( 1 );
        }
    }

    private void printResult( List<File> result )
    {
        if ( classpath )
        {
            Iterator<File> it = result.iterator();
            System.out.print( it.next() );
            while ( it.hasNext() )
            {
                System.out.print( ':' );
                System.out.print( it.next() );
            }
            System.out.println();
        }
        else
        {
            for ( File f : result )
                System.out.println( f );
        }
    }

    private void run()
    {
        try
        {
            try
            {
                boolean error = false;
                List<File> result = new ArrayList<>();

                for ( String s : parameters )
                {
                    if ( s.indexOf( ':' ) > 0 && s.indexOf( ':' ) == s.lastIndexOf( ':' ) )
                        s += ":";
                    if ( s.endsWith( ":" ) )
                        s += "SYSTEM";

                    Artifact artifact = new DefaultArtifact( s );
                    File file = resolver.resolve( new ResolutionRequest( artifact ) ).getArtifactFile();

                    if ( file == null )
                    {
                        error = true;
                        logger.error( "Unable to resolve artifact {}", artifact );
                    }
                    else
                    {
                        result.add( file );
                    }
                }

                if ( error )
                    System.exit( 1 );

                if ( !result.isEmpty() )
                    printResult( result );

                System.exit( 0 );
            }
            catch ( IllegalArgumentException e )
            {
                logger.error( "Failed to parse argument", e );
                System.exit( 1 );
            }
        }
        catch ( Throwable e )
        {
            e.printStackTrace();
            System.exit( 2 );
        }
    }

    public static void main( String[] args )
    {
        Module module = new WireModule( new SpaceModule( new URLClassSpace( ResolverCli.class.getClassLoader() ) ) );
        Injector injector = Guice.createInjector( module );
        ResolverCli cli = injector.getInstance( ResolverCli.class );
        cli.parseArgs( args );
        cli.run();
    }
}