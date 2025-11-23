package dev.webfx.mavenplugin;

import dev.webfx.cli.commands.CommandWorkspace;
import dev.webfx.cli.core.ProjectModule;
import dev.webfx.cli.util.textfile.TextFileReaderWriter;
import dev.webfx.cli.util.xml.XmlUtil;
import dev.webfx.platform.meta.Meta;
import dev.webfx.platform.util.elemental2.Elemental2Util;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

@Mojo(name = "pwa", aggregator = true) // aggregator = true because that goal doesn't need to be run on children
public final class PwaMojo extends AbstractMojo {

    /**
     * projectDirectory from the maven pom.xml file
     */
    @Parameter(property = "projectDirectory", defaultValue = "${basedir}")
    private String projectDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Stop executing build on error or ignore errors
     */
    @Parameter(property = "failOnError", defaultValue = "true")
    private boolean failOnError;

    /**
     * Called when this goal is run
     */
    @Override
    public void execute() throws MojoFailureException {

        // Redirecting CLI logger to Maven logger
        LoggerUtil.configureWebFXLoggerForMaven(getLog());

        Properties metaProperties = new Properties();
        try {
            metaProperties.load(Files.newInputStream(
                Path.of(projectDirectory).resolve("target/classes").resolve(Meta.META_EXE_RESOURCE_FILE_PATH)));
        } catch (IOException e) {
            throw new MojoFailureException(e);
        }

        boolean pwa = metaProperties.getProperty("pwa", "false").equals("true");
        getLog().info("PWA mode is " + (pwa ? "on" : "off"));

        String templateResourceName = pwa ? "pwa-service-worker-on.js" : "pwa-service-worker-off.js";
        try (InputStream is = getClass().getResourceAsStream(templateResourceName)) {
            if (is == null)
                throw new FileNotFoundException(
                    "Template " + templateResourceName + " not found in Maven plugin resources");
            String template = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            Path gwtAppPath = Path.of(projectDirectory)
                .resolve("target")
                .resolve(project.getArtifactId() + "-" + project.getVersion())
                .resolve(project.getArtifactId().replace("-", "_"));

            if (pwa) {
                String mavenBuildTimestamp = metaProperties.getProperty("mavenBuildTimestamp");
                template = template.replace("${mavenBuildTimestamp}", mavenBuildTimestamp);

                // Reading webfx.xml to find essential assets and their strategies
                Map<String, String> assetStrategies = new HashMap<>();
                try {
                    CommandWorkspace workspace = new CommandWorkspace(projectDirectory);
                    ProjectModule projectModule = workspace.getWorkingDevProjectModule();
                    if (projectModule != null) {
                        // dev.webfx.cli.core.ProjectModule returns a dom4j Document
                        org.dom4j.Document webfxXmlDoc = projectModule.getWebFxModuleFile().getDocument();
                        if (webfxXmlDoc != null) {
                            // XmlUtil.lookupNodeList returns List<Node> (dom4j)
                            List<org.dom4j.Node> assetNodes = XmlUtil.lookupNodeList(webfxXmlDoc,
                                "/project/pwa/essential-assets/asset");
                            for (org.dom4j.Node node : assetNodes) {
                                String path = node.getText();
                                String strategy = node.valueOf("@strategy");
                                if (strategy == null || strategy.isEmpty()) {
                                    strategy = "BACKGROUND"; // Default to background prefetch
                                }
                                assetStrategies.put(path, strategy.toUpperCase());
                            }
                        }
                    }
                } catch (Exception e) {
                    getLog().warn("Failed to read webfx.xml for PWA configuration: " + e.getMessage());
                }

                // Build asset manifest by scanning gwtAppPath for .html, .js, .css
                Map<Path, String> manifestMap = new LinkedHashMap<>();
                if (Files.isDirectory(gwtAppPath)) {
                    try (Stream<Path> stream = Files.walk(gwtAppPath)) {
                        stream.filter(Files::isRegularFile)
                            .filter(PwaMojo::includesInCacheAsset)
                            .sorted()
                            .forEach(p -> {
                                try {
                                    Path r = gwtAppPath.relativize(p);
                                    String pathStr = r.toString().replace('\\', '/');
                                    if (!pathStr.equals("index.html")) {
                                        String hash = sha256Hex(p);
                                        manifestMap.put(r, hash);
                                    }
                                } catch (Exception e) {
                                    // If hashing fails, we log and skip this file
                                    getLog().warn("Failed to hash asset: " + p + " - " + e.getMessage());
                                }
                            });
                    }
                } else {
                    getLog().warn("gwtAppPath not found: " + gwtAppPath);
                }

                String assetManifestJson = toJsonAssetObject(manifestMap, assetStrategies, gwtAppPath, getLog());

                String pwaManifestJson = "{\"mavenBuildTimestamp\": \"" + mavenBuildTimestamp
                                         + "\", \"assetManifest\": " + assetManifestJson + "}";
                TextFileReaderWriter.writeTextFileIfNewOrModified(pwaManifestJson,
                    gwtAppPath.resolve("pwa-asset.json"));

                template = template.replace("const ASSET = {}", "const ASSET = " + assetManifestJson);
            }

            TextFileReaderWriter.writeTextFileIfNewOrModified(template,
                gwtAppPath.resolve("pwa-service-worker.js"));
        } catch (Exception e) {
            throw new MojoFailureException(e);
        }
    }

    private static boolean includesInCacheAsset(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return !name.startsWith(".") // ignore hidden files
               && !name.endsWith(".map") // ignore source maps
               && !name.endsWith(".txt"); // ignore text files
    }

    private static String getStrategy(Path p, Map<String, String> assetStrategies) {
        String pathStr = p.toString().replace('\\', '/');
        if (pathStr.startsWith("/"))
            pathStr = pathStr.substring(1);

        if (assetStrategies.containsKey(pathStr)) {
            return assetStrategies.get(pathStr);
        }

        // Automatically pre-cache GWT application file as CRITICAL
        if (p.getFileName().toString().endsWith(".cache.js")) {
            return "CRITICAL";
        }

        return null;
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] bytes = Files.readAllBytes(file);
        byte[] digest = md.digest(bytes);
        return toHex(digest);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit((b) & 0xF, 16));
        }
        return sb.toString();
    }

    private static long gzipSize(Path file) {
        try (java.io.InputStream is = Files.newInputStream(file);
             java.io.OutputStream out = new java.io.OutputStream() {
                 @Override
                 public void write(int b) {
                 }

                 @Override
                 public void write(byte[] b, int off, int len) {
                 }
             };
             java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(out)) {
            CountingOutputStream countingOs = new CountingOutputStream();
            try (java.util.zip.GZIPOutputStream gos = new java.util.zip.GZIPOutputStream(countingOs)) {
                Files.copy(file, gos);
            }
            return countingOs.getCount();
        } catch (Exception e) {
            return 0;
        }
    }

    private static class CountingOutputStream extends java.io.OutputStream {
        private long count = 0;

        @Override
        public void write(int b) {
            count++;
        }

        @Override
        public void write(byte[] b) {
            count += b.length;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            count += len;
        }

        public long getCount() {
            return count;
        }
    }

    // Path-keyed format: { "/path": "hash" } or { "/path": { "strategy":
    // "CRITICAL"|"BACKGROUND", "hash": "...", "size": 123, "gzipSize": 45 } }
    private static String toJsonAssetObject(Map<Path, String> map, Map<String, String> assetStrategies, Path gwtAppPath,
                                            org.apache.maven.plugin.logging.Log log) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<Path, String> e : map.entrySet()) {
            if (!first)
                sb.append(",");
            first = false;
            String path = "/" + e.getKey().toString().replace(java.io.File.separatorChar, '/');
            String hash = e.getValue();
            String strategy = getStrategy(e.getKey(), assetStrategies);
            long size = 0;
            long gzipSize = 0;
            try {
                Path filePath = gwtAppPath.resolve(e.getKey());
                size = Files.size(filePath);
                if (strategy != null) {
                    gzipSize = gzipSize(filePath);
                }

                if (size == 0) {
                    log.warn("File size is 0 for asset: " + e.getKey());
                }
            } catch (IOException ex) {
                log.warn("Failed to get file size for asset: " + e.getKey() + " - " + ex.getMessage());
            }
            sb.append("\n  \"").append(escapeJson(path)).append("\": ");
            if (strategy == null)
                sb.append("\"").append(escapeJson(hash)).append("\"");
            else
                sb.append("{\"strategy\": \"").append(strategy).append("\", \"hash\": \"").append(escapeJson(hash))
                    .append("\", \"size\": ").append(size).append(", \"gzipSize\": ").append(gzipSize).append("}");
        }
        if (!map.isEmpty())
            sb.append("\n");
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

}
