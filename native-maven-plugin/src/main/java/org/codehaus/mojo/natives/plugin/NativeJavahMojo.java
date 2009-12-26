package org.codehaus.mojo.natives.plugin;

/*
 * The MIT License
 * 
 * Copyright (c) 2004, The Codehaus
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.mojo.natives.NativeBuildException;
import org.codehaus.mojo.natives.javah.Javah;
import org.codehaus.mojo.natives.javah.JavahConfiguration;
import org.codehaus.mojo.natives.manager.JavahManager;
import org.codehaus.mojo.natives.manager.NoSuchNativeProviderException;
import org.codehaus.plexus.util.FileUtils;

/**
 * Generate jni include files based on a set of class names
 * @goal javah
 * @phase generate-sources
 * @requiresDependencyResolution compile
 */

public class NativeJavahMojo
    extends AbstractNativeMojo
{

    /**
     * Javah Provider. 
     * @parameter default-value="default"
     * @required
     * @since 1.0-alpha-2
     */
    private String implementation;

    /**
     * List of class names to generate native files. Default is all
     * JNI classes available in the classpath excluding the 
     * transitive dependencies, jars with test scope and provided scope     
     * @parameter 
     * @since 1.0-alpha-2
     */
    private String[] classNames;

    /**
     * Path to javah executable, if present, it will override the default one which bases on architecture type. See 'implementation' argument
     * @parameter
     * @since 1.0-alpha-2
     */
    private File javahPath;

    /**
     * Where to place javah generated file
     * @deprecated use javahOutputDirectory instead
     * @parameter 
     * @since 1.0-alpha-2
     */
    protected File outputDirectory;
    
    /**
     * Where to place javah generated file
     * @parameter default-value="${project.build.directory}/native/javah"
     * @required
     * @since 1.0-alpha-2
     */
    protected File javahOutputDirectory;
    

    /**
     * if configured will be combined with outputDirectory to pass into javah's -o option
     * @parameter 
     * @since 1.0-alpha-2
     */
    private String outputFileName;

    /**
     * Enable javah verbose mode
     * @parameter default-value="false"
     * @since 1.0-alpha-2
     */

    private boolean verbose;

    /**
     * Internal: To look up javah implementation
     * @component
     * @since 1.0-alpha-2
     */

    private JavahManager manager;

    /**
     * For unit test only
     */
    private JavahConfiguration config;

    public void execute()
        throws MojoExecutionException
    {

        //until we remove the deprecated outputDirectory configuration
        if ( this.outputDirectory != null  )
        {
            this.javahOutputDirectory = this.outputDirectory;
        }
        
        try
        {
            this.config = this.createProviderConfiguration();
            this.getJavah().compile( config );
        }
        catch ( NativeBuildException e )
        {
            throw new MojoExecutionException( "Error running javah command", e );
        }

        this.project.addCompileSourceRoot( this.javahOutputDirectory.getAbsolutePath() );

    }

    private Javah getJavah()
        throws MojoExecutionException
    {
        Javah javah;

        try
        {
            javah = this.manager.getJavah( this.implementation );

        }
        catch ( NoSuchNativeProviderException pe )
        {
            throw new MojoExecutionException( pe.getMessage() );
        }

        return javah;
    }

    /**
     * Get all jars in the pom excluding transitive, test, and provided scope dependencies.  
     * @return
     */
    private List getJavahArtifacts()
    {
        List list = new ArrayList();

        List artifacts = this.project.getCompileArtifacts();

        if ( artifacts != null )
        {

            for ( Iterator iter = artifacts.iterator(); iter.hasNext(); )
            {
                Artifact artifact = (Artifact) iter.next();

                //pick up only jar files
                if ( !"jar".equals( artifact.getType() ) )
                {
                    continue;
                }

                //exclude some other scopes
                if ( Artifact.SCOPE_PROVIDED.equals( artifact.getScope() ) )
                {
                    continue;
                }

                list.add( artifact );

            }
        }

        return list;
    }

    /**
     * Build classpaths from dependent jars including project output directory
     * (i.e. classes directory )
     * @return
     */
    private String[] getJavahClassPath()
    {
        List artifacts = this.getJavahArtifacts();

        String[] classPaths = new String[artifacts.size() + 1];

        classPaths[0] = this.project.getBuild().getOutputDirectory();

        Iterator iter = artifacts.iterator();

        for ( int i = 1; i < classPaths.length; ++i )
        {
            Artifact artifact = (Artifact) iter.next();

            classPaths[i] = artifact.getFile().getPath();
        }

        return classPaths;
    }

    /**
     * 
     * Get applicable class names to be "javahed" 
     * 
     */

    private String[] getNativeClassNames()
        throws MojoExecutionException
    {
        if ( this.classNames != null )
        {
            return this.classNames;
        }

        //scan the immediate dependency list for jni classes

        List artifacts = this.getJavahArtifacts();

        List scannedClassNames = new ArrayList();

        for ( Iterator iter = artifacts.iterator(); iter.hasNext(); )
        {
            Artifact artifact = (Artifact) iter.next();

            this.getLog().info( "Parsing " + artifact.getFile() + " for native classes." );

            try
            {
                Enumeration zipEntries = new ZipFile( artifact.getFile() ).entries();

                while ( zipEntries.hasMoreElements() )
                {
                    ZipEntry zipEntry = (ZipEntry) zipEntries.nextElement();

                    if ( "class".equals( FileUtils.extension( zipEntry.getName() ) ) )
                    {
                        ClassParser parser = new ClassParser( artifact.getFile().getPath(), zipEntry.getName() );

                        JavaClass clazz = parser.parse();

                        Method[] methods = clazz.getMethods();

                        for ( int j = 0; j < methods.length; ++j )
                        {
                            if ( methods[j].isNative() )
                            {
                                scannedClassNames.add( clazz.getClassName() );

                                this.getLog().info( "Found native class: " + clazz.getClassName() );

                                break;
                            }
                        }
                    }
                }//endwhile
            }
            catch ( IOException ioe )
            {
                throw new MojoExecutionException( "Error searching for native class in " + artifact.getFile(), ioe );
            }
        }

        return (String[]) scannedClassNames.toArray( new String[scannedClassNames.size()] );
    }

    private JavahConfiguration createProviderConfiguration()
        throws MojoExecutionException
    {
        JavahConfiguration config = new JavahConfiguration();
        config.setWorkingDirectory( this.workingDirectory );
        config.setVerbose( this.verbose );
        config.setOutputDirectory( this.javahOutputDirectory );
        config.setFileName( this.outputFileName );
        config.setClassPaths( this.getJavahClassPath() );
        config.setClassNames( this.getNativeClassNames() );
        config.setJavahPath( this.javahPath );

        return config;
    }

    /**
     * Internal only for test harness purpose
     * @return
     */
    protected JavahConfiguration getJavahConfiguration()
    {
        return this.config;
    }

    /**
     * Internal for unit test only
     */

    protected MavenProject getProject()
    {
        return this.project;
    }
}
