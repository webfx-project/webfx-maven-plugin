package dev.webfx.mavenplugin;

import dev.webfx.cli.mavenplugin.InitGoal;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.components.interactivity.Prompter;
import org.codehaus.plexus.components.interactivity.PrompterException;

@Mojo(name = "init", aggregator = true) // aggregator = true because that goal doesn't need to be run on children
public final class InitMojo extends AbstractMojo {
	
	/**
	 * projectDirectory from the maven pom.xml file to
	 * pass into the webfx-cli export object.
	 */
	@Parameter(property="projectDirectory", defaultValue="${basedir}")
	private String projectDirectory;

	/**
	 * artifact of the project to initialise (expressed as groupId:artifactId:version)
	 */
	@Parameter(property="artifact")
	private String artifact;

	/**
	 * Stop executing build on error or ignore errors
	 */
	@Parameter(property="failOnError", defaultValue="true")
	private boolean failOnError;

	@Component
	private Prompter prompter;

	/**
	 * Called when this goal is run
	 */
	@Override
	public void execute() throws MojoFailureException {

		getLog().debug("-------- export parameters --------");
		getLog().debug("projectDirectory: " + projectDirectory);
		getLog().debug("failOnError: " + failOnError);
		getLog().debug("-----------------------------------");

		try {
			if (artifact == null || artifact.isEmpty())
				artifact = prompter.prompt("Enter your artifact (expressed as groupId:artifactId:version)");
			LoggerUtil.configureWebFXLoggerForMaven(getLog());
			int result = InitGoal.init(projectDirectory, artifact);
			if (failOnError && result != 0) {
				throw new MojoFailureException("Failed to complete init, result=" + result);
			}
		} catch (PrompterException e) {
			throw new RuntimeException(e);
		}

	}
}
