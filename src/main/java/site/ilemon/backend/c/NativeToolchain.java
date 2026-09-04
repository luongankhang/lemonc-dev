package site.ilemon.backend.c;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Target-toolchain abstraction; compiler core does not assume GCC or Clang. */
public final class NativeToolchain {
    private final String compiler;
    public NativeToolchain(String compiler) { if (compiler == null || compiler.isBlank()) throw new IllegalArgumentException("C compiler is empty"); this.compiler = compiler; }
    public String compiler() { return compiler; }
    public static NativeToolchain discover() {
        for (String candidate : List.of("cc", "clang", "gcc", "g++")) if (isAvailable(candidate)) return new NativeToolchain(candidate);
        throw new IllegalStateException("No C compiler found; install GCC, G++, Clang, or a C99-compatible cc");
    }
    public void compile(Path source, Path runtimeSource, Path output) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(compiler, "-x", "c", "-std=c99", "-Wall", "-Wextra", "-Werror", source.toString(), runtimeSource.toString(), "-o", output.toString()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String diagnostics = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException("C compiler failed (" + compiler + "): " + diagnostics.trim());
    }
    private static boolean isAvailable(String command) {
        try { Process process = new ProcessBuilder(command, "--version").redirectErrorStream(true).start(); return process.waitFor() == 0; }
        catch (IOException e) { return false; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }
}
