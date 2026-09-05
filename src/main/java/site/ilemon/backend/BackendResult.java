package site.ilemon.backend;

import java.nio.file.Path;
import java.util.List;

/**
 * Backend-neutral result of lowering a LemonIR module.
 * Carries only the generated artifact paths and identity metadata.
 */
public record BackendResult(List<Path> outputs, String moduleName, String backendName) {

    public BackendResult {
        outputs = List.copyOf(outputs == null ? List.of() : outputs);
    }

    /** Primary artifact (the first generated file). */
    public Path primaryOutput() {
        return outputs.isEmpty() ? null : outputs.get(0);
    }
}