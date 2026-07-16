package io.hexacloud.maven;

import java.nio.file.Path;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

@Mojo(
        name = "overlay",
        defaultPhase = LifecyclePhase.GENERATE_SOURCES
)
public class SourceOverlayMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {

        Path projectRoot = project.getBasedir().toPath();

        Path mainSources = projectRoot.resolve("java/src");
        Path java8Overlay = projectRoot.resolve("java/src-java8");

        Path generatedSources = projectRoot.resolve("target/generated-sources/overlay");

        getLog().info("========================================");
        getLog().info("Source Overlay Plugin");
        getLog().info("========================================");

        getLog().info("Project:");
        getLog().info("  " + projectRoot);

        getLog().info("");

        getLog().info("Main sources:");
        getLog().info("  " + mainSources);

        getLog().info("");

        getLog().info("Java 8 overlay:");
        getLog().info("  " + java8Overlay);

        getLog().info("");

        getLog().info("Generated sources:");
        getLog().info("  " + generatedSources);

        getLog().info("========================================");
        try {

            deleteDirectory(generatedSources);

            copyDirectory(mainSources, generatedSources);

            getLog().info("Copied base sources.");

            String releaseProp = project.getProperties().getProperty("maven.compiler.release");
            String sourceProp = project.getProperties().getProperty("maven.compiler.source");
            String targetProp = project.getProperties().getProperty("maven.compiler.target");

            int releaseFromProperty = extractMajorVersion(releaseProp);
            int releaseFromSource = extractMajorVersion(sourceProp);
            int releaseFromTarget = extractMajorVersion(targetProp);

            int release = releaseFromProperty != -1 ? releaseFromProperty :
                          releaseFromSource != -1 ? releaseFromSource :
                          releaseFromTarget != -1 ? releaseFromTarget : -1;

            getLog().info("Detected: release=" + (releaseProp != null ? releaseProp : "null") +
                          ", source=" + (sourceProp != null ? sourceProp : "null") +
                          ", target=" + (targetProp != null ? targetProp : "null"));
            getLog().info("Resolved Java version: " + (release != -1 ? release : "unknown"));

            // Apply overlays only when targeting Java 8. For Java 21+ use the main java/src as-is.
            if (release == 8) {
                getLog().info("Applying Java 8 overlay...");
                copyDirectory(java8Overlay, generatedSources);
                getLog().info("Applied Java 8 overlay.");
            } else {
                getLog().info("No Java 8 overlay applied for maven.compiler.release=" + release);
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