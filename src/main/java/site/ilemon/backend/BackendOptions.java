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
        boolean verbose,
        boolean arcDebug) {

    public BackendOptions {
        target = target == null ? "jvm" : target;
        arcDebug = arcDebug;
    }

    /**
     * Creates options with arcDebug defaulting to false for backward compatibility.
     */
    public static BackendOptions of(String target, Path sourcePath, Path outputDirectory,
                                    Path outputPath, boolean verbose) {
        return new BackendOptions(target, sourcePath, outputDirectory, outputPath, verbose, false);
    }
}