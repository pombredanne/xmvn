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
package org.fedoraproject.xmvn.resolver;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.eclipse.sisu.launch.InjectedTest;
import org.junit.Test;

import org.fedoraproject.xmvn.artifact.DefaultArtifact;
import org.fedoraproject.xmvn.config.Configuration;
import org.fedoraproject.xmvn.config.Configurator;
import org.fedoraproject.xmvn.config.ResolverSettings;

/**
 * @author Mikolaj Izdebski
 */
public class BasicResolverTest
    extends InjectedTest
{
    /**
     * Test if Plexus can load resolver component.
     * 
     * @throws Exception
     */
    @Test
    public void testComponentLookup()
        throws Exception
    {
        Resolver resolver = lookup( Resolver.class );
        assertNotNull( resolver );
    }

    /**
     * Test if resolver configuration is present and sane.
     * 
     * @throws Exception
     */
    @Test
    public void testConfigurationExistance()
        throws Exception
    {
        Configurator configurator = lookup( Configurator.class );
        assertNotNull( configurator );

        Configuration configuration = configurator.getDefaultConfiguration();
        assertNotNull( configuration );

        ResolverSettings settings = configuration.getResolverSettings();
        assertNotNull( settings );
    }

    /**
     * Test if resolver correctly fails to resolve nonexistent artifact.
     * 
     * @throws Exception
     */
    @Test
    public void testResolutionFailure()
        throws Exception
    {
        Resolver resolver = lookup( Resolver.class );
        ResolutionRequest request =
            new ResolutionRequest( new DefaultArtifact( "some", "nonexistent", "pom", "artifact" ) );
        ResolutionResult result = resolver.resolve( request );
        assertNotNull( result );
        assertNull( result.getArtifactPath() );
    }
}
