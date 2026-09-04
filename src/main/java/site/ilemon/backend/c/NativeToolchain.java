package site.ilemon.backend.c;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Target-toolchain abstraction; compiler core does not assume GCC or Clang. */
public final class NativeToolchain {
    private final String compiler;

    public NativeToolchain(String compiler) {
        if (compiler == null || compiler.isBlank()) throw new IllegalArgumentException("C compiler is empty");
        this.compiler = compiler;
    }

    public String compiler() {
        return compiler;
    }

    public static NativeToolchain discover() {
        // Priority 1: Environment variables
        String envCc = System.getenv("LEMON_CC");
        if (envCc != null && !envCc.isBlank() && isAvailable(envCc)) return new NativeToolchain(envCc);
        envCc = System.getenv("CC");
        if (envCc != null && !envCc.isBlank() && isAvailable(envCc)) return new NativeToolchain(envCc);

        // Priority 2: Well-known Windows MinGW locations
        List<String> windowsMinGwPaths = List.of(
            "C:\\mingw64\\mingw64\\bin\\gcc.exe",
            "C:\\mingw64\\bin\\gcc.exe",
            "C:\\msys64\\mingw64\\bin\\gcc.exe",
            "C:\\msys64\\ucrt64\\bin\\gcc.exe"
        );
        for (String path : windowsMinGwPaths) {
            if (isAvailable(path)) return new NativeToolchain(path);
        }

        // Priority 3: System PATH candidates
        for (String candidate : List.of("gcc", "clang", "cc", "g++")) {
            if (isAvailable(candidate)) return new NativeToolchain(candidate);
        }

        throw new IllegalStateException("No C compiler found; install GCC, G++, Clang, or a C99-compatible cc");
    }

    public Path compile(Path source, Path runtimeSource, Path output) throws IOException, InterruptedException {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (isWindows && !output.getFileName().toString().toLowerCase().endsWith(".exe")) {
            output = output.resolveSibling(output.getFileName().toString() + ".exe");
        }
        if (output.toAbsolutePath().getParent() != null) {
            Files.createDirectories(output.toAbsolutePath().getParent());
        }

        Path runtimeRoot = runtimeSource.toAbsolutePath().getParent();
        List<String> command = new ArrayList<>(List.of(
            compiler,
            "-x", "c",
            "-std=c99",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-I", runtimeRoot.resolve("include").toString(),
            "-I", runtimeRoot.toString(),
            source.toAbsolutePath().toString(),
            runtimeSource.toAbsolutePath().toString(),
            "-o", output.toAbsolutePath().toString(),
            "-lm"
        ));

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String diagnostics = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) {
            throw new IOException("C compiler failed (" + compiler + "): " + diagnostics.trim());
        }
        return output;
    }

    private static boolean isAvailable(String command) {
        try {
            Process process = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
