/*-
 * Copyright (c) 2012-2013 Red Hat, Inc.
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
package org.fedoraproject.maven.installer.impl;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.xml.pull.MXSerializer;
import org.codehaus.plexus.util.xml.pull.XmlSerializer;
import org.eclipse.aether.artifact.Artifact;
import org.fedoraproject.maven.config.InstallerSettings;
import org.fedoraproject.maven.utils.ArtifactUtils;

/**
 * @author Mikolaj Izdebski
 */
class FragmentFile
{
    private final Logger logger;

    private final Map<Artifact, Set<Artifact>> mapping = new HashMap<>();

    private final Set<Artifact> dependencies = new HashSet<>();

    private final Set<Artifact> develDependencies = new HashSet<>();

    private BigDecimal javaVersionRequirement;

    private BigDecimal javaVersionDevelRequirement;

    public FragmentFile( Logger logger )
    {
        this.logger = logger;
    }

    public boolean isEmpty()
    {
        return mapping.isEmpty() && dependencies.isEmpty() && develDependencies.isEmpty()
            && javaVersionRequirement == null && javaVersionDevelRequirement == null;
    }

    private static void addMapping( Map<Artifact, Set<Artifact>> map, Artifact from, Artifact to )
    {
        Set<Artifact> set = map.get( from );
        if ( set == null )
        {
            set = new HashSet<>();
            map.put( from, set );
        }

        set.add( to );
    }

    public void addMapping( Artifact from, Artifact to )
    {
        addMapping( mapping, from, to );

        logger.debug( "Added mapping " + from + " => " + to );
    }

    public void addRuntimeDependency( Artifact dependencyArtifact )
    {
        dependencies.add( dependencyArtifact );
    }

    public void addBuildDependency( Artifact dependencyArtifact )
    {
        develDependencies.add( dependencyArtifact );
    }

    public void addJavaVersionRuntimeDependency( String version )
    {
        BigDecimal versionNumber = new BigDecimal( version );

        if ( javaVersionRequirement == null || javaVersionRequirement.compareTo( versionNumber ) < 0 )
            javaVersionRequirement = versionNumber;
    }

    public void addJavaVersionBuildDependency( String version )
    {
        BigDecimal versionNumber = new BigDecimal( version );

        if ( javaVersionDevelRequirement == null || javaVersionDevelRequirement.compareTo( versionNumber ) < 0 )
            javaVersionDevelRequirement = versionNumber;
    }

    public void optimize()
    {
        Set<Artifact> versionlessArtifacts = new HashSet<>();
        for ( Artifact artifact : mapping.keySet() )
            versionlessArtifacts.add( artifact.setVersion( ArtifactUtils.DEFAULT_VERSION ) );

        for ( Iterator<Artifact> iter = dependencies.iterator(); iter.hasNext(); )
            if ( versionlessArtifacts.contains( iter.next() ) )
                iter.remove();

        for ( Iterator<Artifact> iter = develDependencies.iterator(); iter.hasNext(); )
            if ( versionlessArtifacts.contains( iter.next() ) )
                iter.remove();
    }

    public void write( Path path, boolean writeDevel, InstallerSettings settings )
        throws IOException
    {
        try (Writer writer = new FileWriter( path.toFile() ))
        {
            XmlSerializer s = new MXSerializer();
            s.setProperty( "http://xmlpull.org/v1/doc/properties.html#serializer-indentation", "  " );
            s.setProperty( "http://xmlpull.org/v1/doc/properties.html#serializer-line-separator", "\n" );
            s.setOutput( writer );
            s.startDocument( "US-ASCII", null );
            s.comment( " This depmap file was generated by XMvn " );
            s.text( "\n" );
            s.startTag( null, "dependencyMap" );

            if ( settings.isSkipProvides() )
                s.startTag( null, "skipProvides" ).endTag( null, "skipProvides" );

            if ( javaVersionRequirement != null && !settings.isSkipRequires() )
            {
                s.startTag( null, "requiresJava" );
                s.text( javaVersionRequirement.toString() );
                s.endTag( null, "requiresJava" );
            }

            if ( writeDevel && javaVersionDevelRequirement != null && !settings.isSkipRequires() )
            {
                s.startTag( null, "requiresJavaDevel" );
                s.text( javaVersionDevelRequirement.toString() );
                s.endTag( null, "requiresJavaDevel" );
            }

            for ( Artifact mavenArtifact : mapping.keySet() )
            {
                for ( Artifact jppArtifact : mapping.get( mavenArtifact ) )
                {
                    s.startTag( null, "dependency" );
                    ArtifactUtils.serialize( mavenArtifact, s, null, "maven" );
                    ArtifactUtils.serialize( jppArtifact, s, null, "jpp" );
                    s.endTag( null, "dependency" );
                }
            }

            if ( !settings.isSkipRequires() )
            {
                Set<Artifact> combinedDependencies = new HashSet<>( dependencies );
                if ( writeDevel )
                    combinedDependencies.addAll( develDependencies );

                for ( Artifact dependency : combinedDependencies )
                    ArtifactUtils.serialize( dependency, s, null, "autoRequires" );
            }

            s.endTag( null, "dependencyMap" );
            s.text( "\n" );
            s.endDocument();
        }
    }
}
