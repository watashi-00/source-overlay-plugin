package io.hexacloud.maven;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

@Mojo(
        name = "overlay",
        defaultPhase = LifecyclePhase.GENERATE_SOURCES
)
public class SourceOverlayMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "java/src")
    private String mainSources;

    @Parameter(defaultValue = "target/generated-sources/overlay")
    private String generatedSources;

    @Parameter
    private List<String> excludes;

    @Parameter
    private List<OverlayConfig> overlays;

    public static class OverlayConfig {
        @Parameter(required = true)
        private int jdkVersion;

        @Parameter(required = true)
        private String directory;

        public int getJdkVersion() {
            return jdkVersion;
        }

        public void setJdkVersion(int jdkVersion) {
            this.jdkVersion = jdkVersion;
        }

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }

        @Override
        public String toString() {
            return "Overlay[JDK " + jdkVersion + " -> " + directory + "]";
        }
    }

    @Override
    public void execute() throws MojoExecutionException {
        Path projectRoot = project.getBasedir().toPath();

        Path mainSourcesPath = projectRoot.resolve(mainSources != null ? mainSources : "java/src");
        Path generatedSourcesPath = projectRoot.resolve(generatedSources != null ? generatedSources : "target/generated-sources/overlay");

        getLog().info("Initializing source overlay generation.");
        getLog().debug("Project root: " + projectRoot);
        getLog().info("Main sources directory: " + mainSourcesPath);
        getLog().info("Generated sources output: " + generatedSourcesPath);
        if (excludes != null && !excludes.isEmpty()) {
            getLog().info("Configured exclusions: " + excludes);
        }
        if (overlays != null && !overlays.isEmpty()) {
            getLog().info("Configured overlays: " + overlays);
        }

        try {
            deleteDirectory(generatedSourcesPath);
            copyDirectory(mainSourcesPath, generatedSourcesPath);
            getLog().info("Base sources copied successfully.");

            String releaseProp = project.getProperties().getProperty("maven.compiler.release");
            String sourceProp = project.getProperties().getProperty("maven.compiler.source");
            String targetProp = project.getProperties().getProperty("maven.compiler.target");

            int releaseFromProperty = extractMajorVersion(releaseProp);
            int releaseFromSource = extractMajorVersion(sourceProp);
            int releaseFromTarget = extractMajorVersion(targetProp);

            int release = releaseFromProperty != -1 ? releaseFromProperty :
                          releaseFromSource != -1 ? releaseFromSource :
                          releaseFromTarget != -1 ? releaseFromTarget : -1;

            getLog().info("Resolved target Java version: " + (release != -1 ? release : "unknown"));

            if (release != -1 && overlays != null && !overlays.isEmpty()) {
                // Find all matching overlays: overlay.jdkVersion >= release
                List<OverlayConfig> matchingOverlays = new ArrayList<>();
                for (OverlayConfig ov : overlays) {
                    if (ov.getDirectory() != null && ov.getJdkVersion() >= release) {
                        matchingOverlays.add(ov);
                    }
                }

                if (!matchingOverlays.isEmpty()) {
                    // Sort matching overlays in DESCENDING order of jdkVersion
                    // (e.g. Java 17 is copied first, then Java 8 overlay overwrites/supplements it)
                    matchingOverlays.sort((o1, o2) -> Integer.compare(o2.getJdkVersion(), o1.getJdkVersion()));

                    for (OverlayConfig ov : matchingOverlays) {
                        Path overlayPath = projectRoot.resolve(ov.getDirectory());
                        getLog().info("Applying JDK " + ov.getJdkVersion() + " overlay from: " + ov.getDirectory());
                        copyDirectory(overlayPath, generatedSourcesPath);
                    }
                } else {
                    getLog().info("No matching overlays configured for Java version " + release + ".");
                }
            }

        } catch (IOException e) {
            throw new MojoExecutionException("Failed to copy sources.", e);
        }
    }

    private void copyDirectory(Path source, Path destination) throws IOException {
        if (!Files.exists(source)) {
            getLog().warn("Directory not found: " + source);
            return;
        }

        Files.walk(source).forEach(path -> {
            try {
                Path relative = source.relativize(path);
                
                // Dynamic excludes check
                if (excludes != null) {
                    for (String exclude : excludes) {
                        if (exclude != null && !exclude.trim().isEmpty()) {
                            Path excludePath = Paths.get(exclude.trim());
                            if (relative.startsWith(excludePath)) {
                                return;
                            }
                        }
                    }
                }

                Path target = destination.resolve(relative);

                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(
                            path,
                            target,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES
                    );
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory))
            return;

        Files.walk(directory)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private int extractMajorVersion(String versionProp) {
        if (versionProp == null) return -1;
        try {
            versionProp = versionProp.trim();
            if (versionProp.startsWith("1.")) {
                return Integer.parseInt(versionProp.substring(2));
            } else {
                return Integer.parseInt(versionProp);
            }
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}