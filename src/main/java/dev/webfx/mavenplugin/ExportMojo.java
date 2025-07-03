package dev.webfx.mavenplugin;

import dev.webfx.cli.commands.CommandWorkspace;
import dev.webfx.cli.core.Module;
import dev.webfx.cli.core.*;
import dev.webfx.cli.modulefiles.ExportedWebFxModuleFile;
import dev.webfx.cli.modulefiles.abstr.WebFxModuleFile;
import dev.webfx.cli.util.textfile.TextFileReaderWriter;
import dev.webfx.cli.util.xml.XmlUtil;
import dev.webfx.lib.reusablestream.ReusableStream;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.dom4j.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.Consumer;

@Mojo(name = "export", defaultPhase = LifecyclePhase.INSTALL, aggregator = true) // aggregator = true because that goal doesn't need to be run on children
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

	@Component
	private MavenSession session;

	/**
     * Maven project helper injection
     */
    @Component
    private MavenProjectHelper projectHelper;

	private static Consumer<String> LOGGER;

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

		LOGGER = getLog()::info;

		// This plugin is called either 1) automatically by the profile auto-plugin-webfx declared in webfx-parent, or
		// 2) explicitly through the export goal (dev.webfx:webfx-maven-plugin:0.1.0-SNAPSHOT:export). We never skip
		// 2) but we skip 1) when it's not the `deploy` goal. Indeed, the purpose of the automatic call is to generate
		// the webfx.xml export for its deployment in the `deploy` phase. It's important to generate the file before the
		// `deploy` phase (i.e., in the `install` phase) to ensure it is attached before central-publishing-maven-plugin
		// is called. But it's not necessary (and even not wished as this is time-consuming) to generate it if it's not
		// followed by a deployment (ex: if we invoke `install` and not `deploy`).
		boolean autoSkip = session.getRequest().getGoals().stream().noneMatch(goal ->
			goal.endsWith("deploy") // case 1) => don't skip only if in the `deploy` phase
			|| goal.endsWith("export") // case 2) => never skip
		);
		if (autoSkip) {
			getLog().info("Skipping export because not in the `deploy` goal");
			return;
		}

		File webfxXmlArtifactFile = new File(new File(targetDirectory), "webfx-artifact/webfx.xml");

		// Calling the export() method that generates the webfx.xml artifact
		LoggerUtil.configureWebFXLoggerForMaven(getLog());
		int result = export(projectDirectory, webfxXmlArtifactFile.getAbsolutePath());
		if (failOnError && result < 0) {
			throw new MojoFailureException("Failed to complete export, result=" + result);
		}

		// Attaching the generated artifact, so it will be included in the `install` phase and eventually deployed
		projectHelper.attachArtifact(project, "xml", "webfx", webfxXmlArtifactFile);
		getLog().info("Attached " + webfxXmlArtifactFile.getName() + " to module " + project.getArtifactId() + " for later deploy");
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
			WebFxModuleFile webFxModuleFile = workspace.getWorkingDevProjectModule().getWebFxModuleFile();
			Document document = exportDocument(webFxModuleFile);
			if (document != null) {
				TextFileReaderWriter.writeTextFile(XmlUtil.formatXmlText(document), artifactPath);
				return 1;
			} else {
				Files.copy(webFxModuleFile.getModuleFilePath(), artifactPath, StandardCopyOption.REPLACE_EXISTING);
				return 0;
			}
		} catch (Exception e) {
			Logger.log("ERROR: " + e.getMessage());
			return -1;
		}
	}

	private static Document exportDocument(WebFxModuleFile webFxModuleFile) {
		Document document = webFxModuleFile.getDocument();
		Element rootElement = document.getRootElement();
		Element exportElement = XmlUtil.lookupElement(rootElement, EXPORT_SNAPSHOT_TAG);
		boolean exportNodeWasPresent = exportElement != null;
		if (exportElement != null) {
			XmlUtil.removeChildren(exportElement);
			removeNodeAndPreviousCommentsOrBlankTexts(exportElement);
			XmlUtil.removeNode(exportElement);
		} else {
			exportElement = XmlUtil.createElement(EXPORT_SNAPSHOT_TAG, rootElement);
		}
		if (!webFxModuleFile.generatesExportSnapshot())
			return exportNodeWasPresent ? document : null;
		// Exporting this and children modules in depth
		LOGGER.accept("Exporting children modules");
		final Element finalExportElement = exportElement;
		ProjectModule projectModule = webFxModuleFile.getProjectModule();
		projectModule.getThisAndChildrenModulesInDepth()
				.forEach(pm -> exportChildModuleProject(pm, projectModule, finalExportElement, document));
		// Adding usage to resolve if-uses-java-package and if-uses-java-class directives without downloading the sources
		ReusableStream<ProjectModule> usageCoverage = projectModule.getDirectivesUsageCoverage();
		// First pass: searching all the if-uses-java-package and if-java-classes directives and collecting the packages or classes that require to find the usage
		LOGGER.accept("Collecting usages in directives");
		// We initialize the packages and classes with those always used by the WebFX CLI (hardcoded in the code)
		Set<String> packagesListedInDirectives = new HashSet<>(List.of("java.time", "java.text", "java.lang.ref", "java.util.regex", "netscape.javascript"));
		Set<String> classesListedInDirectives = new HashSet<>(List.of("java.io.EOFException", "java.util.ServiceLoader", "java.util.Properties"));
		usageCoverage
				.forEach(pm -> collectJavaPackagesAndClassesListedInDirectives(pm, packagesListedInDirectives, classesListedInDirectives));
		LOGGER.accept("- packages listed in directives: " + packagesListedInDirectives);
		LOGGER.accept("- classes listed in directives: " + classesListedInDirectives);
		// Third pass: finding usage
		LOGGER.accept("Reporting usages in export");
		Element usagesElement = XmlUtil.createElement("usages", rootElement);
		computeAndPopulateUsagesOfJavaPackagesAndClasses(usagesElement, usageCoverage,
				convertSetToSortedList(packagesListedInDirectives),
				convertSetToSortedList(classesListedInDirectives));
		if (usagesElement.nodeCount() > 0)
			XmlUtil.appendIndentNode(usagesElement, exportElement, true);
		XmlUtil.appendIndentNode(DocumentHelper.createComment(EXPORT_SECTION_COMMENT), rootElement, true);
		XmlUtil.appendIndentNode(exportElement, rootElement, true);
		return document;
	}

	private static <T extends Comparable<? super T>> List<T> convertSetToSortedList(Set<T> set) {
		List<T> list = new ArrayList<>(set);
		Collections.sort(list);
		return list;
	}

	private static void exportChildModuleProject(ProjectModule childModule, ProjectModule projectModule, Element exportElement, Document exportDocument) {
		LOGGER.accept("Exporting child " + childModule.getName());
		Document childDocument = childModule.getWebFxModuleFile().getDocument();
		if (childDocument != null) {
			// Duplicating the XML element, so it can be copied into <export-snapshot/>
			Element sourceElement = childDocument.getRootElement();
			Element childProjectElement = XmlUtil.copyElement(sourceElement, exportDocument);
			// Making the project name explicit (so the import knows what module we are talking about)
			childProjectElement.addAttribute("name", childModule.getName());
			childProjectElement.addAttribute("hasMainJavaSourceDirectory", String.valueOf(childModule.hasMainJavaSourceDirectory()));
			childProjectElement.addAttribute("hasMainWebFxSourceDirectory", String.valueOf(childModule.hasMainWebFxSourceDirectory()));
			// Removing tags that are not necessary for the import: <update-options>, <maven-pom-manual>
			String[] unnecessaryTags = {"update-options", "maven-pom-manual"};
			for (String tag : unnecessaryTags)
				removeNodeAndPreviousCommentsOrBlankTexts(XmlUtil.lookupNode(childProjectElement, tag));
			// Replacing the <modules/> section with the effective modules (so the import doesn't need to download the pom)
			Element modulesElement = XmlUtil.lookupElement(childProjectElement, "modules");
			if (modulesElement != null) {
				XmlUtil.removeChildren(modulesElement);
				childModule.getChildrenModules().forEach(m -> XmlUtil.appendElementWithTextContent(modulesElement, "module", m.getName()));
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
			if (childModule.getWebFxModuleFile().areSourcePackagesAutomaticallyExported()
					// It's also necessary to list the source packages for GWT (as they are listed in module.gwt.xml)
					|| childModule.getTarget().isPlatformSupported(Platform.GWT)) { // TODO: check if it works with -gwt-j2cl modules (ex: charba)
				childMainJavaSourceRootAnalyzer.getSourcePackages()
						.sorted()
						.forEach(p -> XmlUtil.appendElementWithTextContentIfNotAlreadyExists(childProjectElement, "source-packages/package", p, true));
			}
			// Same for the resource packages
			if (childModule.getWebFxModuleFile().areResourcePackagesAutomaticallyExported()) {
				childModule.getFileResourcePackages()
						.sorted()
						.forEach(p -> XmlUtil.appendElementWithTextContentIfNotAlreadyExists(childProjectElement, "resource-packages/package", p, true));
			}
			// Adding a snapshot of the detected used by sources modules (so the import doesn't need to download the sources).
			if (childModule.hasSourceDirectory()) {
				Element detectedUsedBySourceModulesNode = XmlUtil.appendIndentNode(XmlUtil.createElement("used-by-source-modules", exportElement), childProjectElement, true);
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
			XmlUtil.appendIndentNode(childProjectElement, exportElement, true);
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
			// Collecting elements with matching attributes
			packagesOrClassesListedInDirectives.addAll(
					XmlUtil.nodeListToAttributeValueList(
							XmlUtil.lookupElementList(moduleElement, packages ? "//*[@if-uses-java-package]" : "//*[@if-uses-java-class]")
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
							//.filter(ProjectModule.class::isInstance)
							//.map(ProjectModule.class::cast)
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

	// TODO: move these utility methods in XmlUtil

	private static void removeNodeAndPreviousCommentsOrBlankTexts(Node node) {
		if (node != null)
			while (true) {
				Node previousSibling = getPreviousSibling(node);
				if (previousSibling instanceof Comment ||
					previousSibling instanceof Text && previousSibling.getText().isBlank())
					XmlUtil.removeNode(previousSibling);
				else {
					XmlUtil.removeNode(node);
					break;
				}
			}
	}

	private static Node getPreviousSibling(Node node) {
		Element parentNode = node.getParent();
		if (parentNode != null) {
			List<Node> siblings = parentNode.content();
			for (int i = 0; i < siblings.size(); i++) {
				if (siblings.get(i).equals(node)) {
					if (i > 0) {
						return siblings.get(i - 1);
					}
					break;
				}
			}
		}
		return null;
	}

}
