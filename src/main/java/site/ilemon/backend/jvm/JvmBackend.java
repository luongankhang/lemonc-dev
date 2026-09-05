package site.ilemon.backend.jvm;

import site.ilemon.backend.Backend;
import site.ilemon.backend.BackendOptions;
import site.ilemon.backend.BackendResult;
import site.ilemon.exception.CompilerException;
import site.ilemon.ir.IrModule;
import site.ilemon.ir.IrVerifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * JVM backend: lowers the shared LemonIR directly to JVM bytecode and writes
 * a real {@code .class} file — no Jasmin, no intermediate assembly text.
 *
 * <pre>
 *   LemonIR ── JvmBackend ── .class
 * </pre>
 *
 * <p>All JVM-specific logic (descriptors, local slots, stack limits, opcode
 * selection) lives in this package; LemonIR itself stays target-neutral.</p>
 */
public final class JvmBackend implements Backend {

    public static final String DEFAULT_OUTPUT_DIR = "target/lemonc";

    @Override
    public String name() {
        return "jvm";
    }

    @Override
    public BackendResult emit(IrModule module, BackendOptions options) throws IOException {
        Path directory = options != null && options.outputDirectory() != null
                ? options.outputDirectory()
                : Path.of(DEFAULT_OUTPUT_DIR);
        Path classFile = writeClass(module, directory);
        return new BackendResult(List.of(classFile), module.name(), name());
    }

    /** Renders the module to a {@code .class} file inside {@code outputDirectory}. */
    public Path writeClass(IrModule module, Path outputDirectory) throws IOException {
        byte[] bytes = toBytecode(module);
        Files.createDirectories(outputDirectory);
        Path classFile = outputDirectory.resolve(module.name() + ".class");
        Files.write(classFile, bytes);
        return classFile;
    }

    /** Renders the module to JVM class-file bytes (test-friendly). */
    public byte[] toBytecode(IrModule module) {
        if (module == null) {
            throw new CompilerException("cannot emit a null LemonIR module");
        }
        IrVerifier.verify(module);
        JvmClassWriter writer = new JvmClassWriter();
        List<JvmMethod> methods = module.functions().stream()
                .map(function -> new JvmMethodEmitter().emit(function, module, writer))
                .toList();
        return writer.writeClass(module, methods);
    }
}