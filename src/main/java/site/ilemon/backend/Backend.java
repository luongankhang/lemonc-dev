package site.ilemon.backend;

import site.ilemon.ir.IrModule;

import java.io.IOException;

/**
 * Backend-neutral contract for lowering shared LemonIR to a concrete target.
 *
 * <p>Each backend is responsible for its own target lowering:</p>
 * <pre>
 *   LemonIR
 *     ├── JvmBackend → .class
 *     └── CBackend   → .c → native compiler
 * </pre>
 *
 * <p>The interface intentionally exposes only backend-neutral responsibilities:
 * nothing about ASM, Jasmin, JVM instructions, or C source builders leaks here.</p>
 */
public interface Backend {

    /** Stable backend identifier, e.g. "jvm" or "c". */
    String name();

    /**
     * Lowers the shared LemonIR module to this backend's target and writes the
     * resulting artifact(s) to disk.
     *
     * @param module  backend-independent LemonIR (never target-specific)
     * @param options backend-neutral options (target, paths, verbosity)
     * @return paths of the generated artifacts plus module/backend metadata
     */
    BackendResult emit(IrModule module, BackendOptions options) throws IOException;
}