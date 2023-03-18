package dev.webfx.mavenplugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import dev.webfx.cli.WebFxCLI;

@Mojo(name = "cli")
public class CLIGoal extends AbstractMojo{
	
	/**
	 * List of command line arguments from the maven pom.xml file to
	 * pass into the webfx-cli object.
	 */
	@Parameter(property="args")
	private String[] args;
	
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

		getLog().info("-------- cli parameters --------");
		if (args != null) {
			for (String arg : args) {
			    getLog().info("arg: " + arg);
			}
		}		
		getLog().info("failOnError: " + failOnError);
		getLog().info("--------------------------------");

		final int result = WebFxCLI.executeCommand(args);
		
		if (failOnError && result != 0) {
			throw new MojoFailureException("Failed to complete CLI execution, result=" + result);
		}
	}
}
