package site.ilemon.backend.c;

import site.ilemon.ir.IrModule;
import site.ilemon.ir.IrVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** C backend facade. It consumes only backend-independent LemonIR. */
public final class CBackend {
    public String generate(IrModule module) { IrVerifier.verify(module); return new CModuleEmitter().emit(module); }
    public Path generate(IrModule module, Path output) throws IOException { String source = generate(module); Files.createDirectories(output.toAbsolutePath().getParent()); Files.writeString(output, source, StandardCharsets.UTF_8); return output; }
}
