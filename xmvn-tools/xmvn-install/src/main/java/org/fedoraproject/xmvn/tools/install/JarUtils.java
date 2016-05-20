/*-
 * Copyright (c) 2012-2015 Red Hat, Inc.
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
package org.fedoraproject.xmvn.tools.install;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.fedoraproject.xmvn.artifact.Artifact;
import org.fedoraproject.xmvn.utils.ArtifactUtils;

/**
 * @author Mikolaj Izdebski
 */
public class JarUtils
{
    private static final Logger logger = LoggerFactory.getLogger( JarUtils.class );

    /**
     * Heuristically try to determine whether given JAR (or WAR, EAR, ...) file contains native (architecture-dependent)
     * code.
     * <p>
     * Currently this code only checks only for ELF binaries, but that behavior can change in future.
     * 
     * @return {@code true} if native code was found inside given JAR
     */
    public static boolean containsNativeCode( Path jar )
    {
        // From /usr/include/linux/elf.h
        final int ELFMAG0 = 0x7F;
        final int ELFMAG1 = 'E';
        final int ELFMAG2 = 'L';
        final int ELFMAG3 = 'F';

        try (ZipInputStream jis = new ZipInputStream( Files.newInputStream( jar ) ))
        {
            ZipEntry ent;
            while ( ( ent = jis.getNextEntry() ) != null )
            {
                if ( ent.isDirectory() )
                    continue;
                if ( jis.read() == ELFMAG0 && jis.read() == ELFMAG1 && jis.read() == ELFMAG2 && jis.read() == ELFMAG3 )
                {
                    logger.debug( "Native code found inside {}: {}", jar, ent.getName() );
                    return true;
                }
            }

            logger.trace( "Native code not found inside {}", jar );
            return false;
        }
        catch ( IOException e )
        {
            logger.debug( "I/O exception caught when trying to determine whether JAR contains native code: {}", jar, e );
            return false;
        }
    }

    static class NativeMethodFound
        extends RuntimeException
    {
        private static final long serialVersionUID = 1;

        final String className;

        final String methodName;

        final String methodSignature;

        NativeMethodFound( String className, String methodName, String methodSignature )
        {
            this.className = className;
            this.methodName = methodName;
            this.methodSignature = methodSignature;
        }
    }

    /**
     * Heuristically try to determine whether given JAR (or WAR, EAR, ...) file is using native (architecture-dependent)
     * code.
     * <p>
     * Currently this code only checks if any class file declares Java native methods, but that behavior can change in
     * future.
     * 
     * @return {@code true} given JAR as found inside to use native code
     */
    public static boolean usesNativeCode( Path jar )
    {
        try (ZipInputStream jis = new ZipInputStream( Files.newInputStream( jar ) ))
        {
            ZipEntry ent;
            while ( ( ent = jis.getNextEntry() ) != null )
            {
                final String entryName = ent.getName();
                if ( ent.isDirectory() || !entryName.endsWith( ".class" ) )
                    continue;

                new ClassReader( jis ).accept( new ClassVisitor( Opcodes.ASM5 )
                {
                    @Override
                    public MethodVisitor visitMethod( int flags, String name, String desc, String sig, String[] exc )
                    {
                        if ( ( flags & Opcodes.ACC_NATIVE ) != 0 )
                            throw new NativeMethodFound( entryName, name, sig );

                        return super.visitMethod( flags, name, desc, sig, exc );
                    }
                }, ClassReader.SKIP_CODE );
            }

            return false;
        }
        catch ( NativeMethodFound e )
        {
            logger.debug( "Native method {}({}) found in {}: {}", e.methodName, e.methodSignature, jar, e.className );
            return true;
        }
        catch ( IOException e )
        {
            logger.debug( "I/O exception caught when trying to determine whether JAR uses native code: {}", jar, e );
            return false;
        }
    }

    private static void putAttribute( Manifest manifest, String key, String value, String defaultValue )
    {
        if ( defaultValue == null || !value.equals( defaultValue ) )
        {
            Attributes attributes = manifest.getMainAttributes();
            attributes.putValue( key, value );
            logger.trace( "Injected field {}: {}", key, value );
        }
        else
        {
            logger.trace( "Not injecting field {} (it has default value \"{}\")", key, defaultValue );
        }
    }

    /**
     * OpenJDK has a sanity check that prevents adding duplicate entries to ZIP streams. The problem is that some of
     * JARs we try to inject manifests to (especially the ones created by Gradle) already contain duplicate entries, so
     * manifest injection would always fail for them with "ZipException: duplicate entry".
     * <p>
     * This function tries to work around this OpenJDK sanity check, effectively allowing creating ZIP files with
     * duplicated entries. It should be called on particular ZIP output stream before adding each duplicate entry.
     * 
     * @param zipOutputStream ZIP stream to hack
     */
    private static void openJdkAvoidDuplicateEntryHack( ZipOutputStream zipOutputStream )
    {
        try
        {
            Field namesField = ZipOutputStream.class.getDeclaredField( "names" );
            namesField.setAccessible( true );
            Collection<?> names = (Collection<?>) namesField.get( zipOutputStream );
            names.clear();
        }
        catch ( ReflectiveOperationException e )
        {
            // This hack relies on OpenJDK internals and therefore is not ugarranteed to work. Ignore failures.
        }
    }

    /**
     * Inject artifact coordinates into manifest of specified JAR (or WAR, EAR, ...) file. The file is modified
     * in-place.
     * 
     * @param targetJar
     * @param artifact
     */
    public static void injectManifest( Path targetJar, Artifact artifact )
    {
        logger.trace( "Trying to inject manifest to {}", artifact );

        try (JarInputStream jis = new JarInputStream( Files.newInputStream( targetJar ) ))
        {
            Manifest mf = jis.getManifest();
            if ( mf == null )
            {
                logger.trace( "Manifest injection skipped: no pre-existing manifest found to update" );
                return;
            }

            putAttribute( mf, ArtifactUtils.MF_KEY_GROUPID, artifact.getGroupId(), null );
            putAttribute( mf, ArtifactUtils.MF_KEY_ARTIFACTID, artifact.getArtifactId(), null );
            putAttribute( mf, ArtifactUtils.MF_KEY_EXTENSION, artifact.getExtension(), Artifact.DEFAULT_EXTENSION );
            putAttribute( mf, ArtifactUtils.MF_KEY_CLASSIFIER, artifact.getClassifier(), "" );
            putAttribute( mf, ArtifactUtils.MF_KEY_VERSION, artifact.getVersion(), Artifact.DEFAULT_VERSION );

            targetJar = targetJar.toRealPath();
            Files.delete( targetJar );

            try (JarOutputStream jos = new JarOutputStream( Files.newOutputStream( targetJar ), mf ))
            {
                byte[] buf = new byte[512];
                JarEntry entry;
                while ( ( entry = jis.getNextJarEntry() ) != null )
                {
                    openJdkAvoidDuplicateEntryHack( jos );
                    jos.putNextEntry( entry );

                    int sz;
                    while ( ( sz = jis.read( buf ) ) > 0 )
                        jos.write( buf, 0, sz );
                }
            }
            catch ( IOException e )
            {
                // Re-throw exceptions that occur when processing JAR file after reading header and manifest.
                throw new RuntimeException( e );
            }

            logger.trace( "Manifest injected successfully" );
        }
        catch ( IOException e )
        {
            logger.debug( "I/O exception caught when trying to read JAR: {}", targetJar );
        }
    }
}
