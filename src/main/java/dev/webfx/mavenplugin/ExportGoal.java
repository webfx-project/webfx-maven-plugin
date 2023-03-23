package dev.webfx.mavenplugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import dev.webfx.cli.mavenplugin.Export;

@Mojo(name = "export")
public class ExportGoal extends AbstractMojo{
	
	/**
	 * projectDirectory from the maven pom.xml file to
	 * pass into the webfx-cli export object.
	 */
	@Parameter(property="projectDirectory")
	private String projectDirectory;

	/**
	 * targetDirectory from the maven pom.xml file to
	 * pass into the webfx-cli export object.
	 */
	@Parameter(property="targetDirectory")
	private String targetDirectory;
	
	/**
	 * Stop executing build on error or ignore errors
	 */
	@Parameter(property="failOnError", defaultValue="true")
	private boolean failOnError;
	
	/**
	 * Called when this goal is run, passes args into the command line interface
	 */
	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {

		getLog().info("-------- export parameters --------");
		getLog().info("projectDirectory: " + projectDirectory);
		getLog().info("targetDirectory: " + targetDirectory);
		getLog().info("failOnError: " + failOnError);
		getLog().info("--------------------------------");

		final int result = Export.export(projectDirectory, targetDirectory);

		if (failOnError && result != 0) {
			throw new MojoFailureException("Failed to complete export, result=" + result);
		}
	}
}
