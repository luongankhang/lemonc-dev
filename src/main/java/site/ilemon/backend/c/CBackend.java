package site.ilemon.backend.c;

import site.ilemon.backend.Backend;
import site.ilemon.backend.BackendOptions;
import site.ilemon.backend.BackendResult;
import site.ilemon.ir.IrModule;
import site.ilemon.ir.IrVerifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * C backend facade. It consumes only backend-independent LemonIR and emits
 * portable C99 that is compiled by a native toolchain.
 *
 * <pre>
 *   LemonIR ── CBackend ── .c ── GCC/Clang ── native executable
 * </pre>
 */
public final class CBackend implements Backend {

    private final ConstantPropagation constantPropagation = new ConstantPropagation();
    private final DeadStoreElimination deadStoreElimination = new DeadStoreElimination();

    /** Pure C source generation; unchanged by the multi-backend refactor. */
    public String generate(IrModule module) {
        IrVerifier.verify(module);
        
        // Apply C backend optimizations
        // constantPropagation.optimize(module);  // Disabled: changes generated C code structure
        deadStoreElimination.optimize(module);
        
        return new CModuleEmitter().emit(module);
    }

    public Path generate(IrModule module, Path output) throws IOException {
        String source = generate(module);
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, source, StandardCharsets.UTF_8);
        return output;
    }

    @Override
    public String name() {
        return "c";
    }

    @Override
    public BackendResult emit(IrModule module, BackendOptions options) throws IOException {
        String source = generate(module);
        Path sourcePath = options != null && options.sourcePath() != null
                ? options.sourcePath()
                : Path.of("Main.lemon");

        Path cPath;
        if (options != null && options.outputPath() != null
                && options.outputPath().toString().endsWith(".c")) {
            cPath = options.outputPath();
        } else {
            cPath = Path.of(sourcePath.toString().replaceAll("\\.lemon$", ".c"));
        }
        if (cPath.toAbsolutePath().getParent() != null) {
            Files.createDirectories(cPath.toAbsolutePath().getParent());
        }
        Files.writeString(cPath, source, StandardCharsets.UTF_8);

        List<Path> outputs = new ArrayList<>();
        outputs.add(cPath);

        if (options != null && "c".equalsIgnoreCase(options.target())) {
            Path exePath;
            if (options.outputPath() != null && !options.outputPath().toString().endsWith(".c")) {
                exePath = options.outputPath();
            } else {
                exePath = Path.of(sourcePath.toString().replaceAll("\\.lemon$", ""));
            }
            NativeToolchain toolchain = NativeToolchain.discover();
            Path runtimeSource = Path.of("runtime", "lemon_runtime.c");
            if (!Files.exists(runtimeSource)) {
                runtimeSource = Path.of(System.getProperty("user.dir"), "runtime", "lemon_runtime.c");
            }
            try {
                Path executable = toolchain.compile(cPath, runtimeSource, exePath);
                outputs.add(executable);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("native build interrupted", interrupted);
            }
        }
        return new BackendResult(outputs, module.name(), name());
    }
}