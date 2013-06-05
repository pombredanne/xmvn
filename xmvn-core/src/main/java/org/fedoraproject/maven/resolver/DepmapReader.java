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
package org.fedoraproject.maven.resolver;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.CharBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.codehaus.plexus.logging.Logger;
import org.fedoraproject.maven.config.ResolverSettings;
import org.fedoraproject.maven.model.Artifact;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * @author Mikolaj Izdebski
 */
class DepmapReader
{
    private final Logger logger;

    private final DependencyMap depmap;

    private final CountDownLatch ready = new CountDownLatch( 1 );

    private static Map<File, DepmapReader> readers = new TreeMap<>();

    DepmapReader( Logger logger )
    {
        this.logger = logger;
        depmap = new DependencyMap( logger );
    }

    public static DependencyMap readArtifactMap( File root, ResolverSettings settings, Logger logger )
    {
        boolean notYetInitialized = false;
        DepmapReader reader = null;

        synchronized ( readers )
        {
            reader = readers.get( root );
            if ( reader == null )
            {
                reader = new DepmapReader( logger );
                readers.put( root, reader );
                notYetInitialized = true;
            }
        }

        if ( notYetInitialized )
        {
            try
            {
                reader.readArtifactMap( root, reader.depmap, settings );
            }
            finally
            {
                reader.ready.countDown();
            }
        }
        else
        {
            try
            {
                reader.ready.await();
            }
            catch ( InterruptedException e )
            {
                return null;
            }
        }

        return reader.depmap;
    }

    void readArtifactMap( File root, DependencyMap map, ResolverSettings settings )
    {
        for ( String path : settings.getMetadataRepositories() )
        {
            File file = root.toPath().resolve( path ).toFile();

            if ( file.isDirectory() )
            {
                String flist[] = file.list();
                if ( flist != null )
                {
                    Arrays.sort( flist );
                    for ( String fragFilename : flist )
                        tryLoadDepmapFile( map, new File( file, fragFilename ) );
                }
            }
            else
            {
                tryLoadDepmapFile( map, file );
            }
        }
    }

    private void tryLoadDepmapFile( DependencyMap map, File fragment )
    {
        try
        {
            loadDepmapFile( map, fragment );
        }
        catch ( IOException e )
        {
            logger.warn( "Could not load depmap file " + fragment.getAbsolutePath() + ": ", e );
        }
    }

    private void loadDepmapFile( DependencyMap map, File file )
        throws IOException
    {
        logger.debug( "Loading depmap file: " + file );
        Document mapDocument = buildDepmapModel( file );

        NodeList depNodes = mapDocument.getElementsByTagName( "dependency" );

        for ( int i = 0; i < depNodes.getLength(); i++ )
        {
            Element depNode = (Element) depNodes.item( i );

            Artifact from = getArtifactDefinition( depNode, "maven" );
            if ( from == Artifact.DUMMY )
                throw new IOException();

            Artifact to = getArtifactDefinition( depNode, "jpp" );
            map.addMapping( from.clearVersionAndExtension(), to.clearVersionAndExtension() );
        }
    }

    private Document buildDepmapModel( File file )
        throws IOException
    {
        try
        {
            DocumentBuilderFactory fact = DocumentBuilderFactory.newInstance();
            fact.setNamespaceAware( true );
            DocumentBuilder builder = fact.newDocumentBuilder();
            String contents = wrapFragment( file );
            try (Reader reader = new StringReader( contents ))
            {
                InputSource source = new InputSource( reader );
                return builder.parse( source );
            }
        }
        catch ( ParserConfigurationException e )
        {
            throw new IOException( e );
        }
        catch ( SAXException e )
        {
            throw new IOException( e );
        }
    }

    private String wrapFragment( File fragmentFile )
        throws IOException
    {
        CharBuffer contents = readFile( fragmentFile );

        if ( contents.length() >= 5 && contents.subSequence( 0, 5 ).toString().equalsIgnoreCase( "<?xml" ) )
        {
            return contents.toString();
        }

        StringBuilder buffer = new StringBuilder();
        buffer.append( "<dependencies>" );
        buffer.append( contents );
        buffer.append( "</dependencies>" );
        return buffer.toString();
    }

    private CharBuffer readFile( File file )
        throws IOException
    {
        try (FileInputStream fragmentStream = new FileInputStream( file ))
        {
            try (FileChannel channel = fragmentStream.getChannel())
            {
                MappedByteBuffer buffer = channel.map( FileChannel.MapMode.READ_ONLY, 0, channel.size() );
                return Charset.defaultCharset().decode( buffer );
            }
        }
    }

    private Artifact getArtifactDefinition( Element root, String childTag )
        throws IOException
    {
        NodeList jppNodeList = root.getElementsByTagName( childTag );

        if ( jppNodeList.getLength() == 0 )
            return Artifact.DUMMY;

        Element element = (Element) jppNodeList.item( 0 );

        NodeList nodes = element.getElementsByTagName( "groupId" );
        if ( nodes.getLength() != 1 )
            throw new IOException();
        String groupId = nodes.item( 0 ).getTextContent().trim();

        nodes = element.getElementsByTagName( "artifactId" );
        if ( nodes.getLength() != 1 )
            throw new IOException();
        String artifactId = nodes.item( 0 ).getTextContent().trim();

        nodes = element.getElementsByTagName( "version" );
        if ( nodes.getLength() > 1 )
            throw new IOException();
        String version = null;
        if ( nodes.getLength() != 0 )
            version = nodes.item( 0 ).getTextContent().trim();

        return new Artifact( groupId, artifactId, version );
    }
}
