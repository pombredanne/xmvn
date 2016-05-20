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
package org.fedoraproject.xmvn.repository.impl;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;

import org.fedoraproject.xmvn.repository.Repository;
import org.fedoraproject.xmvn.repository.RepositoryConfigurator;

/**
 * Factory creating compound repositories.
 * <p>
 * <strong>WARNING</strong>: This class is part of internal implementation of XMvn and it is marked as public only for
 * technical reasons. This class is not part of XMvn API. Client code using XMvn should <strong>not</strong> reference
 * it directly.
 * 
 * @author Mikolaj Izdebski
 */
@Named( "compound" )
@Singleton
public class CompoundRepositoryFactory
    extends AbstractRepositoryFactory
{
    private final RepositoryConfigurator configurator;

    @Inject
    public CompoundRepositoryFactory( RepositoryConfigurator configurator )
    {
        this.configurator = configurator;
    }

    @Override
    public Repository getInstance( Xpp3Dom filter, Properties properties, Xpp3Dom configuration, String namespace )
    {
        Path prefix = null;
        if ( properties.containsKey( "prefix" ) )
            prefix = Paths.get( properties.getProperty( "prefix" ) );

        if ( configuration.getChildCount() != 1 || !configuration.getChild( 0 ).getName().equals( "repositories" ) )
            throw new RuntimeException( "compound repository expects configuration "
                + "with exactly one child element: <repositories>" );
        configuration = configuration.getChild( 0 );

        List<Repository> slaveRepositories = new ArrayList<>();
        for ( Xpp3Dom child : configuration.getChildren() )
        {
            if ( !child.getName().equals( "repository" ) || child.getChildCount() > 0 )
                throw new RuntimeException( "All children of <repositories> must be <repository> text nodes" );

            Repository slaveRepository = configurator.configureRepository( child.getValue().trim(), namespace );
            slaveRepositories.add( slaveRepository );
        }

        if ( StringUtils.isEmpty( namespace ) )
            namespace = properties.getProperty( "namespace", "" );

        return new CompoundRepository( namespace, prefix, slaveRepositories );
    }
}
