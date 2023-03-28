package dev.webfx.mavenplugin;

import dev.webfx.cli.mavenplugin.ExportGoal;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

import java.io.File;

@Mojo(name = "export", aggregator = true) // aggregator = true because that goal doesn't need to be run on children
public final class ExportMojo extends AbstractMojo {
	
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
	 * Called when this goal is run
	 */
	@Override
	public void execute() throws MojoFailureException {

		getLog().debug("-------- export parameters --------");
		getLog().debug("projectDirectory: " + projectDirectory);
		getLog().debug("targetDirectory: " + targetDirectory);
		getLog().debug("failOnError: " + failOnError);
		getLog().debug("-----------------------------------");

		File webfxXmlArtifactFile = new File(new File(targetDirectory), "webfx-artifact/webfx.xml");

		// Calling the WebFX CLI ExportGoal.export() command to generate the webfx.xml artifact
		int result = ExportGoal.export(projectDirectory, webfxXmlArtifactFile.getAbsolutePath());
		if (failOnError && result != 0) {
			throw new MojoFailureException("Failed to complete export, result=" + result);
		}

		// Attaching the generated artifact, so it will be included in the `install` phase, and eventually deployed
		projectHelper.attachArtifact(project, "xml", "webfx", webfxXmlArtifactFile);
	}
}
