/*-
 * Copyright (c) 2012 Red Hat, Inc.
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
package org.fedoraproject.maven.rpminstall.plugin;

import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

@Mojo( name = "builddep", aggregator = true, requiresDependencyResolution = ResolutionScope.TEST )
public class BuilddepMojo
    extends AbstractMojo
    implements DependencyVisitor
{
    @Parameter( defaultValue = "${reactorProjects}", readonly = true, required = true )
    private List<MavenProject> reactorProjects;

    private final Set<String> buildDeps = new TreeSet<>();

    private final Set<String> reactorArtifacts = new TreeSet<>();

    private BigDecimal javaVersion = null;

    @Override
    public void visitBuildDependency( String groupId, String artifactId )
    {
        buildDeps.add( "mvn(" + groupId + ":" + artifactId + ")" );
    }

    @Override
    public void visitRuntimeDependency( String groupId, String artifactId )
    {
        visitBuildDependency( groupId, artifactId );
    }

    @Override
    public void visitJavaVersionDependency( BigDecimal version )
    {
        if ( javaVersion == null || javaVersion.compareTo( version ) < 0 )
            javaVersion = version;
    }

    @Override
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        try
        {
            for ( MavenProject project : reactorProjects )
            {
                String groupId = project.getGroupId();
                String artifactId = project.getArtifactId();
                reactorArtifacts.add( "mvn(" + groupId + ":" + artifactId + ")" );

                Model rawModel = DependencyExtractor.getRawModel( project );
                DependencyExtractor.generateRawRequires( rawModel, this );
                DependencyExtractor.generateEffectiveBuildRequires( project.getModel(), this );

                if ( !project.getPackaging().equals( "pom" ) )
                    DependencyExtractor.getJavaCompilerTarget( project, this );
            }

            try (PrintStream ps = new PrintStream( ".xmvn-builddep" ))
            {
                ps.println( "BuildRequires:  maven-local" );

                if ( javaVersion != null )
                {
                    String epoch = javaVersion.compareTo( new BigDecimal( "1.5" ) ) >= 0 ? "1:" : "";
                    ps.println( "BuildRequires:  java-devel >= " + epoch + javaVersion );
                }

                buildDeps.removeAll( reactorArtifacts );
                for ( String dep : buildDeps )
                    ps.println( "BuildRequires:  " + dep );
            }
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Failed to generate build dependencies", e );
        }
    }
}