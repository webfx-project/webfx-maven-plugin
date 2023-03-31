package dev.webfx.mavenplugin;

import dev.webfx.cli.commands.CommandWorkspace;
import dev.webfx.cli.commands.Update;
import dev.webfx.cli.core.Logger;
import dev.webfx.cli.core.MavenUtil;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.transfer.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;

import java.util.List;

@Mojo(name = "update", aggregator = true) // aggregator = true because that goal doesn't need to be run on children
public class UpdateMojo extends AbstractMojo {

	@Parameter(property="projectDirectory", defaultValue="${basedir}")
	private String projectDirectory;

	/**
	 * Stop executing build on error or ignore errors
	 */
	@Parameter(property="failOnError", defaultValue="true")
	private boolean failOnError;


	// ==== The remaining parameters are injected by Maven and used internally to implement the artifact downloader ====

	@Parameter(defaultValue = "${session}", required = true, readonly = true)
	private MavenSession session;

	@Component
	private ArtifactResolver artifactResolver;

	@Parameter(defaultValue = "${project.remoteArtifactRepositories}", readonly = true, required = true)
	private List<ArtifactRepository> pomRemoteRepositories;


	// =================================== Main entry point to implement the goal ======================================

	/**
	 * Called when this goal is run, passes args into the command line interface
	 */
	@Override
	public void execute() throws MojoFailureException {

		// Injecting the Maven logger to the WebFX CLI
		LoggerUtil.configureWebFXLoggerForMaven(getLog());
		// Injecting the Maven artifact downloader to the WebFX CLI
		MavenUtil.setMavenArtifactDownloader(this::downloadArtifact);

		try {
			CommandWorkspace workspace = new CommandWorkspace(projectDirectory);
			Update.execute(null, null, false, workspace);
		} catch (Exception e) {
			if (failOnError) {
				throw new MojoFailureException("Failed to complete update: " + e.getMessage());
			}
			Logger.log("ERROR: " + e.getMessage());
		}
	}

	// ============================================ Artifact downloader ================================================
	// ======= (faster than the default one in WebFX CLI as it doesn't require a Maven restart between 2 calls) ========

	public void downloadArtifact(String groupId, String artifactId, String version, String classifier) {
		try {
			ProjectBuildingRequest buildingRequest =
					new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
			buildingRequest.setRemoteRepositories(pomRemoteRepositories);

			DefaultArtifactCoordinate artifactCoordinate = new DefaultArtifactCoordinate();
			artifactCoordinate.setGroupId(groupId);
			artifactCoordinate.setArtifactId(artifactId);
			artifactCoordinate.setVersion(version);
			artifactCoordinate.setClassifier(classifier);

			artifactResolver.resolveArtifact(buildingRequest, artifactCoordinate);

		} catch (ArtifactResolverException e) {
			getLog().warn("Couldn't download artifact: " + e.getMessage());
		}
	}

}
