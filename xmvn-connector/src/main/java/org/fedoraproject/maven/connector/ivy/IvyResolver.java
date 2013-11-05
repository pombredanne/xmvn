package org.fedoraproject.maven.connector.ivy;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.ivy.core.IvyContext;
import org.apache.ivy.core.cache.ArtifactOrigin;
import org.apache.ivy.core.module.descriptor.Artifact;
import org.apache.ivy.core.module.descriptor.DefaultModuleDescriptor;
import org.apache.ivy.core.module.descriptor.DependencyDescriptor;
import org.apache.ivy.core.module.descriptor.ModuleDescriptor;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.core.report.ArtifactDownloadReport;
import org.apache.ivy.core.report.DownloadReport;
import org.apache.ivy.core.report.DownloadStatus;
import org.apache.ivy.core.report.MetadataArtifactDownloadReport;
import org.apache.ivy.core.resolve.DownloadOptions;
import org.apache.ivy.core.resolve.IvyNode;
import org.apache.ivy.core.resolve.ResolveData;
import org.apache.ivy.core.resolve.ResolvedModuleRevision;
import org.apache.ivy.plugins.parser.ModuleDescriptorParser;
import org.apache.ivy.plugins.parser.ModuleDescriptorParserRegistry;
import org.apache.ivy.plugins.repository.Resource;
import org.apache.ivy.plugins.repository.ResourceDownloader;
import org.apache.ivy.plugins.repository.file.FileRepository;
import org.apache.ivy.plugins.repository.file.FileResource;
import org.apache.ivy.plugins.repository.url.URLResource;
import org.apache.ivy.plugins.resolver.AbstractResolver;
import org.apache.ivy.plugins.resolver.util.MDResolvedResource;
import org.apache.ivy.plugins.resolver.util.ResolvedResource;
import org.apache.ivy.util.Message;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.logging.Logger;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.WorkspaceReader;
import org.fedoraproject.maven.utils.LoggingUtils;

public class IvyResolver
    extends AbstractResolver
{
    public static WorkspaceReader XMVN;

    private static File locateArtifact( ModuleRevisionId revision, String extension )
    {
        try
        {
            if ( XMVN == null )
            {
                PlexusContainer container = new DefaultPlexusContainer();
                LoggingUtils.setLoggerThreshold( container.lookup( Logger.class ), true );
                XMVN = container.lookup( WorkspaceReader.class, "ide" );
            }
        }
        catch ( PlexusContainerException | ComponentLookupException e )
        {
            Message.error( e.getMessage() );
            e.printStackTrace();
        }

        return XMVN.findArtifact( new DefaultArtifact( revision.getOrganisation(), revision.getName(), extension,
                                                       revision.getRevision() ) );
    }

    private final ResourceDownloader downloader = new ResourceDownloader()
    {
        @Override
        public void download( Artifact artifact, Resource resource, File dest )
            throws IOException
        {
            if ( dest.exists() )
                dest.delete();
            Files.createSymbolicLink( Paths.get( dest.getAbsolutePath() ),
                                      locateArtifact( artifact.getModuleRevisionId(), artifact.getType() ).toPath() );
        }
    };

    @Override
    public ResolvedModuleRevision getDependency( DependencyDescriptor dd, ResolveData data )
        throws ParseException
    {
        IvyContext context = IvyContext.pushNewCopyContext();
        DependencyDescriptor systemDd = dd;
        DependencyDescriptor nsDd = fromSystem( dd );
        context.setDependencyDescriptor( systemDd );
        context.setResolveData( data );

        ModuleRevisionId systemMrid = systemDd.getDependencyRevisionId();
        ModuleRevisionId nsMrid = nsDd.getDependencyRevisionId();

        ResolvedResource ivyRef = findIvyFileRef( nsDd, data );
        boolean isDynamic = getSettings().getVersionMatcher().isDynamic( systemMrid );

        ModuleDescriptor systemMd;
        ResolvedModuleRevision rmr = null;
        if ( ivyRef == null )
        {
            ModuleDescriptor nsMd =
                DefaultModuleDescriptor.newDefaultInstance( nsMrid, nsDd.getAllDependencyArtifacts() );
            ResolvedResource artifactRef = findFirstArtifactRef( nsMd, nsDd, data );
            if ( artifactRef == null )
            {
                Message.verbose( "Artifact not found: " + systemMrid );
                return null;
            }

            Message.verbose( "POM for artifact " + systemMrid + " not found" );
            if ( isDynamic )
            {
                nsMd.setResolvedModuleRevisionId( ModuleRevisionId.newInstance( nsMrid, artifactRef.getRevision() ) );
            }
            systemMd = toSystem( nsMd );
            MetadataArtifactDownloadReport madr = new MetadataArtifactDownloadReport( systemMd.getMetadataArtifact() );
            madr.setDownloadStatus( DownloadStatus.NO );
            madr.setSearched( true );
            return new ResolvedModuleRevision( this, this, systemMd, madr, true );
        }

        if ( ivyRef instanceof MDResolvedResource )
        {
            rmr = ( (MDResolvedResource) ivyRef ).getResolvedModuleRevision();
        }
        if ( rmr == null )
        {
            rmr = parse( ivyRef, systemDd, data );
            if ( rmr == null )
            {
                Message.verbose( "Unresolved dependency: no ivy file found for " + systemMrid + ": using default data" );
                return null;
            }
        }
        if ( !rmr.getReport().isDownloaded() && rmr.getReport().getLocalFile() != null )
        {
            return checkLatest( systemDd, rmr, data );
        }

        ModuleDescriptor nsMd = rmr.getDescriptor();

        // check descriptor data is in sync with resource revision and names
        systemMd = toSystem( nsMd );
        if ( systemMd instanceof DefaultModuleDescriptor )
        {
            DefaultModuleDescriptor defaultMd = (DefaultModuleDescriptor) systemMd;
            ModuleRevisionId revision = getRevision( ivyRef, systemMrid, systemMd );
            defaultMd.setModuleRevisionId( revision );
            defaultMd.setResolvedModuleRevisionId( revision );
        }
        return new ResolvedModuleRevision( this, this, systemMd, toSystem( rmr.getReport() ), true );
    }

    private ModuleRevisionId getRevision( ResolvedResource ivyRef, ModuleRevisionId askedMrid, ModuleDescriptor md )
        throws ParseException
    {
        Map allAttributes = new HashMap();
        allAttributes.putAll( md.getQualifiedExtraAttributes() );
        allAttributes.putAll( askedMrid.getQualifiedExtraAttributes() );

        String revision = ivyRef.getRevision();
        if ( revision == null )
        {
            Message.debug( "no revision found in reference for " + askedMrid );
            if ( getSettings().getVersionMatcher().isDynamic( askedMrid ) )
            {
                if ( md.getModuleRevisionId().getRevision() == null )
                {
                    revision = "working@" + getName();
                }
                else
                {
                    Message.debug( "using " + askedMrid );
                    revision = askedMrid.getRevision();
                }
            }
            else
            {
                Message.debug( "using " + askedMrid );
                revision = askedMrid.getRevision();
            }
        }

        return ModuleRevisionId.newInstance( askedMrid.getOrganisation(), askedMrid.getName(), askedMrid.getBranch(),
                                             revision, allAttributes );
    }

    private ResolvedResource findFirstArtifactRef( ModuleDescriptor md, DependencyDescriptor dd, ResolveData data )
    {
        ResolvedResource ret = null;
        String[] conf = md.getConfigurationsNames();
        for ( int i = 0; i < conf.length; i++ )
        {
            Artifact[] artifacts = md.getArtifacts( conf[i] );
            for ( int j = 0; j < artifacts.length; j++ )
            {
                ret = getArtifactRef( artifacts[j], data.getDate() );
                if ( ret != null )
                {
                    return ret;
                }
            }
        }
        return null;
    }

    private ResolvedResource getArtifactRef( Artifact artifact, Date date )
    {
        IvyContext.getContext().set( getName() + ".artifact", artifact );
        try
        {
            ResolvedResource ret = findArtifactRef( artifact, date );
            if ( ret == null && artifact.getUrl() != null )
            {
                URL url = artifact.getUrl();
                Message.verbose( "\tusing url for " + artifact + ": " + url );
                Resource resource;
                if ( "file".equals( url.getProtocol() ) )
                {
                    resource = new FileResource( new FileRepository(), new File( url.getPath() ) );
                }
                else
                {
                    resource = new URLResource( url );
                }
                ret = new ResolvedResource( resource, artifact.getModuleRevisionId().getRevision() );
            }
            return ret;
        }
        finally
        {
            IvyContext.getContext().set( getName() + ".artifact", null );
        }
    }

    private ResolvedResource findArtifactRef( Artifact artifact, Date date )
    {
        ModuleRevisionId revision = artifact.getId().getModuleRevisionId();

        File artifactFile = locateArtifact( artifact.getModuleRevisionId(), artifact.getType() );

        if ( artifactFile != null )
        {
            Resource resource = new FileResource( new FileRepository(), artifactFile );
            return new ResolvedResource( resource, revision.getRevision() );
        }

        return null;
    }

    private ResolvedModuleRevision parse( final ResolvedResource mdRef, DependencyDescriptor dd, ResolveData data )
        throws ParseException
    {

        DependencyDescriptor nsDd = dd;
        dd = toSystem( nsDd );

        ModuleRevisionId mrid = dd.getDependencyRevisionId();
        ModuleDescriptorParser parser = ModuleDescriptorParserRegistry.getInstance().getParser( mdRef.getResource() );
        if ( parser == null )
        {
            Message.warn( "no module descriptor parser available for " + mdRef.getResource() );
            return null;
        }
        Message.verbose( "\t" + getName() + ": found md file for " + mrid );
        Message.verbose( "\t\t=> " + mdRef );
        Message.debug( "\tparser = " + parser );

        ModuleRevisionId resolvedMrid = mrid;

        // first check if this dependency has not yet been resolved
        if ( getSettings().getVersionMatcher().isDynamic( mrid ) )
        {
            resolvedMrid = ModuleRevisionId.newInstance( mrid, mdRef.getRevision() );
            IvyNode node = data.getNode( resolvedMrid );
            if ( node != null && node.getModuleRevision() != null )
            {
                // this revision has already be resolved : return it
                if ( node.getDescriptor() != null && node.getDescriptor().isDefault() )
                {
                    Message.verbose( "\t" + getName() + ": found already resolved revision: " + resolvedMrid
                        + ": but it's a default one, maybe we can find a better one" );
                }
                else
                {
                    Message.verbose( "\t" + getName() + ": revision already resolved: " + resolvedMrid );
                    node.getModuleRevision().getReport().setSearched( true );
                    return node.getModuleRevision();
                }
            }
        }

        Artifact moduleArtifact = parser.getMetadataArtifact( resolvedMrid, mdRef.getResource() );
        return getRepositoryCacheManager().cacheModuleDescriptor( this, mdRef, dd, moduleArtifact, downloader,
                                                                  getCacheOptions( data ) );
    }

    @Override
    public ResolvedResource findIvyFileRef( DependencyDescriptor depDescriptor, ResolveData data )
    {
        ModuleRevisionId revision = depDescriptor.getDependencyRevisionId();

        File pomPath = locateArtifact( revision, "pom" );
        if ( pomPath == null )
            return null;

        File ivyPath = Converter.mvn2ivy( pomPath );
        if ( ivyPath == null )
            return null;

        return new ResolvedResource( new FileResource( new FileRepository(), pomPath ), "SYSTEM" );
    }

    @Override
    public DownloadReport download( Artifact[] artifacts, DownloadOptions options )
    {
        DownloadReport report = new DownloadReport();

        for ( Artifact artifact : artifacts )
        {
            ArtifactDownloadReport artifactReport = new ArtifactDownloadReport( artifact );
            File artifactPath = locateArtifact( artifact.getModuleRevisionId(), artifact.getType() );

            if ( artifactPath != null )
            {
                artifactReport.setArtifactOrigin( new ArtifactOrigin( artifact, false, artifactPath.toString() ) );
                artifactReport.setLocalFile( artifactPath );
                artifactReport.setDownloadStatus( DownloadStatus.SUCCESSFUL );
            }
            else
            {
                artifactReport.setDownloadStatus( DownloadStatus.FAILED );
            }

            report.addArtifactReport( artifactReport );
        }

        return report;
    }

    @Override
    public void publish( Artifact artifact, File artifactFile, boolean overwrite )
        throws IOException
    {
        throw new IOException( "Publishing Ivy artifacts through XMvn is not supported." );
    }
}
