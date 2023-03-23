package dev.webfx.mavenplugin;

import java.io.File;

import org.apache.maven.plugin.AbstractMojo;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

import dev.webfx.cli.mavenplugin.Export;

@Mojo(name = "export")
public class ExportMojo extends AbstractMojo{
	
	/**
	 * projectDirectory from the maven pom.xml file to
	 * pass into the webfx-cli export object.
	 */
	@Parameter(property="projectDirectory", defaultValue="${basedir}")
	private String projectDirectory;

	/**
	 * targetDirectory from the maven pom.xml file to
	 * pass into the webfx-cli export object.
	 */
	@Parameter(property="targetDirectory", defaultValue="${project.build.directory}")
	private String targetDirectory;
	
	/**
	 * Stop executing build on error or ignore errors
	 */
	@Parameter(property="failOnError", defaultValue="true")
	private boolean failOnError;
	
	/**
	 * Maven project injection
	 */
	@Parameter(readonly = true, defaultValue = "${project}" )
    private MavenProject project;

    /**
     * Maven project helper injection
     */
    @Component
    private MavenProjectHelper projectHelper;

	/**
	 * Called when this goal is run, passes args into the command line interface
	 */
	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {

		getLog().debug("-------- export parameters --------");
		getLog().debug("projectDirectory: " + projectDirectory);
		getLog().debug("targetDirectory: " + targetDirectory);
		getLog().debug("failOnError: " + failOnError);
		getLog().debug("-----------------------------------");

		final int result = Export.export(projectDirectory, targetDirectory);
		if (failOnError && result != 0) {
			throw new MojoFailureException("Failed to complete export, result=" + result);
		}
		
		projectHelper.attachArtifact(project, "xml", "webfx", 
		    new File(new File(targetDirectory), "webfx-artifact/webfx.xml"));
	}
}
