package dev.webfx.mavenplugin;

import dev.webfx.cli.commands.CommandWorkspace;
import dev.webfx.cli.core.Module;
import dev.webfx.cli.core.*;
import dev.webfx.cli.modulefiles.DevWebFxModuleFile;
import dev.webfx.cli.modulefiles.ExportedWebFxModuleFile;
import dev.webfx.cli.util.textfile.TextFileReaderWriter;
import dev.webfx.cli.util.xml.XmlUtil;
import dev.webfx.lib.reusablestream.ReusableStream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.w3c.dom.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

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

		// Calling the export() method that generates the webfx.xml artifact
		LoggerUtil.configureWebFXLoggerForMaven(getLog());
		int result = export(projectDirectory, webfxXmlArtifactFile.getAbsolutePath());
		if (failOnError && result != 0) {
			throw new MojoFailureException("Failed to complete export, result=" + result);
		}

		// Attaching the generated artifact, so it will be included in the `install` phase, and eventually deployed
		projectHelper.attachArtifact(project, "xml", "webfx", webfxXmlArtifactFile);
	}

	private final static String EXPORT_SNAPSHOT_TAG = "export-snapshot";
	private final static String EXPORT_SECTION_COMMENT = "\n" +
			"\n" +
			"     ******************************************************************************************************************* \n" +
			"     ******************************* Section managed by WebFX (DO NOT EDIT MANUALLY) *********************************** \n" +
			"     ******************************************************************************************************************* \n" +
			"\n" +
			"     <export-snapshot> allows a much faster import of this WebFX library into another project. It's a self-contained\n" +
			"     image of this and children modules. All information required for the import of this library is present in this\n" +
			"     single file. The export snapshot is optional, and a WebFX library that doesn't generate it can still be imported\n" +
			"     into another project, but WebFX will then need to download all individual webfx.xml files for every children\n" +
			"     modules, together with their pom and sources. Knowing that each download requires a maven invocation that takes\n" +
			"     at least 3s (sometimes 10s or more), the export snapshot brings a significant performance improvement in the\n" +
			"     import process.\n" +
			"\n" +
			"     ";

	// This method is called by the WebFX Maven plugin
	public static int export(String projectDirectory, String webfxXmlArtifactPath) {
		try {
			CommandWorkspace workspace = new CommandWorkspace(projectDirectory);
			Path artifactPath = Path.of(webfxXmlArtifactPath);
			Files.createDirectories(artifactPath.getParent());
			DevWebFxModuleFile webFxModuleFile = workspace.getWorkingDevProjectModule().getWebFxModuleFile();
			Document document = exportDocument(webFxModuleFile);
			if (document != null) {
				TextFileReaderWriter.writeTextFile(XmlUtil.formatXmlText(document), artifactPath);
			} else {
				Files.copy(webFxModuleFile.getModuleFilePath(), artifactPath, StandardCopyOption.REPLACE_EXISTING);
			}
			return 0;
		} catch (Exception e) {
			Logger.log("ERROR: " + e.getMessage());
			return -1;
		}
	}

	private static Document exportDocument(DevWebFxModuleFile webFxModuleFile) {
		Document document = webFxModuleFile.getDocument();
		Node exportNode = XmlUtil.lookupNode(document, EXPORT_SNAPSHOT_TAG);
		boolean exportNodeWasPresent = exportNode != null;
		if (exportNode != null) {
			XmlUtil.removeChildren(exportNode);
			removeNodeAndPreviousCommentsOrBlankTexts(exportNode);
			XmlUtil.removeNode(exportNode);
		} else
			exportNode = document.createElement(EXPORT_SNAPSHOT_TAG);
		if (!webFxModuleFile.generatesExportSnapshot())
			return exportNodeWasPresent ? document : null;
		// Exporting this and children modules in depth
		final Node finalExportNode = exportNode;
		DevProjectModule projectModule = webFxModuleFile.getProjectModule();
		projectModule.getThisAndChildrenModulesInDepth()
				.forEach(pm -> exportChildModuleProject(pm, projectModule, finalExportNode));
		// Adding usage to resolve if-uses-java-package and if-uses-java-class directives without downloading the sources
		ReusableStream<ProjectModule> usageCoverage = projectModule.getDirectivesUsageCoverage();
		// First pass: searching all the if-uses-java-package and if-java-classes directives and collecting the packages or classes that require to find the usage
		Set<String> packagesListedInDirectives = new HashSet<>(); // To be populated
		Set<String> classesListedInDirectives = new HashSet<>(); // To be populated
		usageCoverage
				.forEach(pm -> collectJavaPackagesAndClassesListedInDirectives(pm, packagesListedInDirectives, classesListedInDirectives));
		//System.out.println("packagesListedInDirectives: " + packagesListedInDirectives);
		//System.out.println("classesListedInDirectives: " + classesListedInDirectives);
		// Third pass: finding usage
		Element usagesElement = document.createElement("usages");
		computeAndPopulateUsagesOfJavaPackagesAndClasses(usagesElement, usageCoverage,
				convertSetToSortedList(packagesListedInDirectives),
				convertSetToSortedList(classesListedInDirectives));
		if (usagesElement.hasChildNodes())
			XmlUtil.appendIndentNode(usagesElement, exportNode, true);
		XmlUtil.appendIndentNode(document.createComment(EXPORT_SECTION_COMMENT), document.getDocumentElement(), true);
		XmlUtil.appendIndentNode(exportNode, document.getDocumentElement(), true);
		return document;
	}

	private static <T extends Comparable<? super T>> List<T> convertSetToSortedList(Set<T> set) {
		List<T> list = new ArrayList<>(set);
		Collections.sort(list);
		return list;
	}

	private static void exportChildModuleProject(ProjectModule childModule, DevProjectModule projectModule, Node exportNode) {
		Document childDocument = childModule.getWebFxModuleFile().getDocument();
		if (childDocument != null) {
			Document document = exportNode.getOwnerDocument();
			// Duplicating the xml element, so it can be copied into <export-snapshot/>
			Element childProjectElement = (Element) document.importNode(childDocument.getDocumentElement(), true);
			// Making the project name explicit (so the import knows what module we are talking about)
			childProjectElement.setAttribute("name", childModule.getName());
			// Removing tags that are not necessary for the import: <update-options>, <maven-pom-manual>
			String[] unnecessaryTags = {"update-options", "maven-pom-manual"};
			for (String tag : unnecessaryTags)
				removeNodeAndPreviousCommentsOrBlankTexts(XmlUtil.lookupNode(childProjectElement, tag));
			// Replacing the <modules/> section with the effective modules (so the import doesn't need to download the pom)
			Node modulesNode = XmlUtil.lookupNode(childProjectElement, "modules");
			if (modulesNode != null) {
				XmlUtil.removeChildren(modulesNode);
				childModule.getChildrenModules().forEach(m -> XmlUtil.appendElementWithTextContent(modulesNode, "module", m.getName()));
			}
			// Trying to export the packages for the third-party libraries (so the import doesn't need to download their sources)
			new ExportedWebFxModuleFile(projectModule, childProjectElement)
					.getRequiredThirdPartyLibraryModules()
					.filter(LibraryModule::shouldBeDownloadedInM2)
					// Also excluding the snapshots because the exported packages may change in the future
					.filter(libraryModule -> !libraryModule.getVersion().contains("SNAPSHOT"))
					.forEach(libraryModule -> {
						ProjectModule libraryProjectModule = projectModule.searchRegisteredProjectModule(libraryModule.getName(), true);
						if (libraryProjectModule != null)
							libraryProjectModule.getMainJavaSourceRootAnalyzer().getSourcePackages()
									.forEach(p -> XmlUtil.appendElementWithTextContentIfNotAlreadyExists(libraryModule.getXmlNode(), "exported-packages/package", p, true));
					});
			// Adding a snapshot of the source packages, because they must be listed in executable GWT modules, and also
			// because we want to be able to evaluate the <source-packages/> directive without having to download the sources
			JavaSourceRootAnalyzer childMainJavaSourceRootAnalyzer = childModule.getMainJavaSourceRootAnalyzer();
			childMainJavaSourceRootAnalyzer.getSourcePackages()
					.sorted()
					.forEach(p -> XmlUtil.appendElementWithTextContentIfNotAlreadyExists(childProjectElement, "source-packages/package", p, true));
			// Adding a snapshot of the detected used by sources modules (so the import doesn't need to download the sources).
			if (childModule.hasSourceDirectory()) {
				Node detectedUsedBySourceModulesNode = XmlUtil.appendIndentNode(document.createElement("used-by-source-modules"), childProjectElement, true);
				childMainJavaSourceRootAnalyzer.getDetectedByCodeAnalyzerSourceDependencies()
						.map(ModuleDependency::getDestinationModule)
						.map(Module::getName)
						.sorted()
						.forEach(m -> XmlUtil.appendElementWithTextContent(detectedUsedBySourceModulesNode, "module", m));
			}
			// Adding a snapshot of the used required java services
			childMainJavaSourceRootAnalyzer.getUsedRequiredJavaServices().forEach(js -> XmlUtil.appendElementWithTextContentIfNotAlreadyExists(childProjectElement, "used-services/required-service", js, true));
			// Adding a snapshot of the used optional java services
			childMainJavaSourceRootAnalyzer.getUsedOptionalJavaServices().forEach(js -> XmlUtil.appendElementWithTextContentIfNotAlreadyExists(childProjectElement, "used-services/optional-service", js, true));
			XmlUtil.appendIndentNode(childProjectElement, exportNode, true);
		}
	}

	private static void collectJavaPackagesAndClassesListedInDirectives(ProjectModule pm, Set<String> packagesListedInDirectives /* to populate */, Set<String> classesListedInDirectives /* to populate */) {
		Element moduleElement = pm.getWebFxModuleFile().getModuleElement();
		collectJavaPackagesOrClassesListedInDirectives(moduleElement, packagesListedInDirectives, true);
		collectJavaPackagesOrClassesListedInDirectives(moduleElement, classesListedInDirectives, false);
	}

	private static void collectJavaPackagesOrClassesListedInDirectives(Element moduleElement, Set<String> packagesOrClassesListedInDirectives, boolean packages) {
		if (moduleElement != null) {
			// Collecting elements with matching text content
			packagesOrClassesListedInDirectives.addAll(
					XmlUtil.nodeListToTextContentList(
							XmlUtil.lookupNodeList(moduleElement, packages ? "//if-uses-java-package" : "//if-uses-java-class")
					)
			);
			// Collecting element with matching attributes
			packagesOrClassesListedInDirectives.addAll(
					XmlUtil.nodeListToAttributeValueList(
							XmlUtil.lookupNodeList(moduleElement, packages ? "//*[@if-uses-java-package]" : "//*[@if-uses-java-class]")
							, packages ? "if-uses-java-package" : "if-uses-java-class"
					)
			);
		}
	}

	private static void computeAndPopulateUsagesOfJavaPackagesAndClasses(Element usagesElement, ReusableStream<ProjectModule> searchScope, List<String> packagesListedInDirectives, List<String> classesListedInDirectives) {
		computeAndPopulateUsagesOfJavaPackagesOrClasses(usagesElement, searchScope, packagesListedInDirectives, true);
		computeAndPopulateUsagesOfJavaPackagesOrClasses(usagesElement, searchScope, classesListedInDirectives, false);
	}

	private static void computeAndPopulateUsagesOfJavaPackagesOrClasses(Element usagesElement, ReusableStream<ProjectModule> searchScope, List<String> packagesOrClassesListedInDirectives, boolean packages) {
		packagesOrClassesListedInDirectives
				.forEach(packageOrClassToFindUsage -> {
					ReusableStream<ProjectModule> modulesUsingJavaPackagesOrClasses = searchScope
							//.flatMap(ProjectModule::getThisAndTransitiveModules) // already done, no?
							.filter(ProjectModule.class::isInstance)
							.map(ProjectModule.class::cast)
							.distinct()
							.filter(m -> usesJavaPackageOrClass(m, packageOrClassToFindUsage, packages))
							.sorted();
					Element packageElement = XmlUtil.appendElementWithAttributeIfNotAlreadyExists(usagesElement, packages ? "java-package" : "java-class", "name", packageOrClassToFindUsage, true);
					modulesUsingJavaPackagesOrClasses
							.forEach(pm -> XmlUtil.appendElementWithTextContent(packageElement, "module", pm.getName()));
				});
	}

	private static boolean usesJavaPackageOrClass(ProjectModule pm, String packageOrClassToFindUsage, boolean isPackage) {
		return isPackage ? pm.getMainJavaSourceRootAnalyzer().usesJavaPackage(packageOrClassToFindUsage) : pm.getMainJavaSourceRootAnalyzer().usesJavaClass(packageOrClassToFindUsage);
	}

	private static void removeNodeAndPreviousCommentsOrBlankTexts(Node node) {
		if (node != null)
			while (true) {
				Node previousSibling = node.getPreviousSibling();
				if (previousSibling instanceof Comment ||
						previousSibling instanceof Text && previousSibling.getTextContent().isBlank())
					XmlUtil.removeNode(previousSibling);
				else {
					XmlUtil.removeNode(node);
					break;
				}
			}
	}

}
