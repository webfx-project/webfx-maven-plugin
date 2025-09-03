package dev.webfx.mavenplugin;

import dev.webfx.cli.util.textfile.TextFileReaderWriter;
import dev.webfx.platform.meta.Meta;
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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
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
            metaProperties.load(Files.newInputStream(Path.of(projectDirectory).resolve("target/classes").resolve(Meta.META_EXE_RESOURCE_FILE_PATH)));
        } catch (IOException e) {
            throw new MojoFailureException(e);
        }

        boolean pwa = metaProperties.getProperty("pwa", "false").equals("true");
        getLog().info("PWA mode is " + (pwa ? "on" : "off"));

        String templateResourceName = pwa ? "pwa-service-worker-on.js" : "pwa-service-worker-off.js";
        try (InputStream is = getClass().getResourceAsStream(templateResourceName)) {
            if (is == null)
                throw new FileNotFoundException("Template " + templateResourceName + " not found in Maven plugin resources");
            String template = new String(is.readAllBytes(), StandardCharsets.UTF_8);

            Path gwtAppPath = Path.of(projectDirectory)
                .resolve("target")
                .resolve(project.getArtifactId() + "-" + project.getVersion())
                .resolve(project.getArtifactId().replace("-", "_"));

            if (pwa) {
                String mavenBuildTimestamp = metaProperties.getProperty("mavenBuildTimestamp");
                template = template.replace("${mavenBuildTimestamp}", mavenBuildTimestamp);

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
                                      if (!r.toString().equals("/index.html")) {
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

                String assetManifestJson = toJsonAssetObject(manifestMap);

                String pwaManifestJson = "{\"mavenBuildTimestamp\": \"" + mavenBuildTimestamp + "\", \"assetManifest\": " + assetManifestJson + "}";
                TextFileReaderWriter.writeTextFileIfNewOrModified(pwaManifestJson, gwtAppPath.resolve("pwa-asset.json"));

                template = template.replace("const ASSET = {}", "const ASSET = " + assetManifestJson);
            }

            TextFileReaderWriter.writeTextFileIfNewOrModified(template, gwtAppPath.resolve("pwa-service-worker.js"));
        } catch (Exception e) {
            throw new MojoFailureException(e);
        }
    }

    private static boolean includesInCacheAsset(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".html")
               || name.endsWith(".js")
               || name.endsWith(".css")
               || name.endsWith(".svg")
               || name.endsWith(".png")
               || name.endsWith(".jpg")
               || name.endsWith(".jpeg")
               || name.endsWith(".ttf")
               || name.endsWith(".mp3");
    }

    private static Boolean shouldBePreCached(Path p) {
        return null; // not specified => will be DEFAULT_PRE_CACHE
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

    // Path-keyed format: { "/path": "hash" } or { "/path": { "preCache": true|false, "hash": "..." } }
    private static String toJsonAssetObject(Map<Path, String> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<Path, String> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            String path = "/" + e.getKey().toString().replace(java.io.File.separatorChar, '/');
            String hash = e.getValue();
            Boolean preCache = shouldBePreCached(e.getKey());
            sb.append("\n  \"").append(escapeJson(path)).append("\": ");
            if (preCache == null)
                sb.append("\"").append(escapeJson(hash)).append("\"");
            else
                sb.append("{\"preCache\": ").append(preCache).append(", \"hash\": \"").append(escapeJson(hash)).append("\"}");
        }
        if (!map.isEmpty()) sb.append("\n");
        sb.append("}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int)c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

}
