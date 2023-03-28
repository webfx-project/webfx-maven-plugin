package dev.webfx.mavenplugin;

import dev.webfx.cli.mavenplugin.UpdateGoal;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

@Mojo(name = "update", aggregator = true) // aggregator = true because that goal doesn't need to be run on children
public class UpdateMojo extends AbstractMojo {

	@Parameter(property="projectDirectory", defaultValue="${basedir}")
	private String projectDirectory;

	/**
	 * Stop executing build on error or ignore errors
	 */
	@Parameter(property="failOnError", defaultValue="true")
	private boolean failOnError;

	/**
	 * Called when this goal is run, passes args into the command line interface
	 */
	@Override
	public void execute() throws MojoFailureException {

		LoggerUtil.configureWebFXLoggerForMaven(getLog());
		int result = UpdateGoal.update(projectDirectory);

		if (failOnError && result != 0) {
			throw new MojoFailureException("Failed to complete update, result=" + result);
		}
	}
}
