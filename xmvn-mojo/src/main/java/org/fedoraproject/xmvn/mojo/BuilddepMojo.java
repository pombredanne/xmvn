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
package org.fedoraproject.xmvn.mojo;

import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.pull.MXSerializer;
import org.codehaus.plexus.util.xml.pull.XmlSerializer;

import org.fedoraproject.xmvn.artifact.Artifact;
import org.fedoraproject.xmvn.dependency.DependencyExtractionRequest;
import org.fedoraproject.xmvn.dependency.DependencyExtractionResult;
import org.fedoraproject.xmvn.dependency.DependencyExtractor;
import org.fedoraproject.xmvn.model.ModelFormatException;
import org.fedoraproject.xmvn.resolver.ResolutionRequest;
import org.fedoraproject.xmvn.resolver.ResolutionResult;
import org.fedoraproject.xmvn.resolver.Resolver;
import org.fedoraproject.xmvn.utils.ArtifactUtils;

/**
 * @author Mikolaj Izdebski
 */
@Mojo( name = "builddep", aggregator = true, requiresDependencyResolution = ResolutionScope.NONE )
@Named
public class BuilddepMojo
    extends AbstractMojo
{
    @Parameter( defaultValue = "${reactorProjects}", readonly = true, required = true )
    private List<MavenProject> reactorProjects;

    private final Resolver resolver;

    private final DependencyExtractor buildDependencyExtractor;

    @Inject
    public BuilddepMojo( Resolver resolver,
                         @Named( DependencyExtractor.BUILD ) DependencyExtractor buildDependencyExtractor )
    {
        this.resolver = resolver;
        this.buildDependencyExtractor = buildDependencyExtractor;
    }

    @Override
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        try
        {
            Set<Artifact> reactorArtifacts = new LinkedHashSet<>();
            Set<Artifact> dependencies = new LinkedHashSet<>();
            Set<Artifact> resolvedDependencies = new LinkedHashSet<>();
            BigDecimal javaVersion = null;

            for ( MavenProject project : reactorProjects )
            {
                Artifact projectArtifact = Utils.aetherArtifact( project.getArtifact() );
                projectArtifact = projectArtifact.setFile( project.getFile() );

                DependencyExtractionRequest request = new DependencyExtractionRequest();
                request.setModelPath( projectArtifact.getFile().toPath() );
                DependencyExtractionResult result = buildDependencyExtractor.extract( request );

                dependencies.addAll( result.getDependencyArtifacts() );

                if ( result.getJavaVersion() != null )
                {
                    BigDecimal thisVersion = new BigDecimal( result.getJavaVersion() );
                    if ( javaVersion == null || javaVersion.compareTo( thisVersion ) < 0 )
                        javaVersion = thisVersion;
                }

                reactorArtifacts.add( projectArtifact );
                for ( org.apache.maven.artifact.Artifact attachedArtifact : project.getAttachedArtifacts() )
                    reactorArtifacts.add( Utils.aetherArtifact( attachedArtifact ) );
            }

            dependencies.removeAll( reactorArtifacts );

            for ( Artifact dependencyArtifact : dependencies )
            {
                ResolutionRequest request = new ResolutionRequest();
                request.setArtifact( dependencyArtifact );
                ResolutionResult result = resolver.resolve( request );

                if ( result.getArtifactFile() != null )
                {
                    dependencyArtifact = dependencyArtifact.setFile( result.getArtifactFile() );
                    dependencyArtifact = dependencyArtifact.setVersion( result.getCompatVersion() );
                }
                else
                {
                    dependencyArtifact = dependencyArtifact.setVersion( Artifact.DEFAULT_VERSION );
                }

                resolvedDependencies.add( dependencyArtifact );
            }

            try (Writer writer = new FileWriter( ".xmvn-builddep" ))
            {
                XmlSerializer s = new MXSerializer();
                s.setProperty( "http://xmlpull.org/v1/doc/properties.html#serializer-indentation", "  " );
                s.setProperty( "http://xmlpull.org/v1/doc/properties.html#serializer-line-separator", "\n" );
                s.setOutput( writer );
                s.startDocument( "US-ASCII", null );
                s.comment( " Build dependencies generated by XMvn " );
                s.text( "\n" );
                s.startTag( null, "dependencies" );

                for ( Artifact dependencyArtifact : resolvedDependencies )
                    ArtifactUtils.serialize( dependencyArtifact, s, null, "dependency" );

                s.endTag( null, "dependencies" );
            }
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Failed to generate build dependencies", e );
        }
        catch ( ModelFormatException e )
        {
            throw new MojoExecutionException( "Failed to generate build dependencies", e );
        }
    }
}
