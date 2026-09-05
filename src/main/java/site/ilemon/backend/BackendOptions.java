package site.ilemon.backend;

import java.nio.file.Path;

/**
 * Backend-neutral compilation options shared by every backend.
 * No backend-specific field may be added here; targets keep their own details
 * inside their own package.
 */
public record BackendOptions(
        String target,
        Path sourcePath,
        Path outputDirectory,
        Path outputPath,
        boolean verbose) {

    public BackendOptions {
        target = target == null ? "jvm" : target;
    }
}