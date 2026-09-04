package site.ilemon.backend.c;

import site.ilemon.ir.IrModule;

public final class CModuleEmitter {
    public String emit(IrModule module) {
        StringBuilder out = new StringBuilder("#include <stdbool.h>\n#include <stdint.h>\n#include <stddef.h>\n#include \"lemon_runtime.h\"\n\n");
        out.append("typedef struct { unsigned char _opaque; } lemon_opaque_t;\n");
        out.append("\n");
        CFunctionEmitter functions = new CFunctionEmitter();
        module.functions().forEach(function -> out.append(functions.emit(function)).append('\n'));
        return out.toString();
    }
}
