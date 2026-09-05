package site.ilemon.backend.c;

import site.ilemon.ir.IrFunction;
import site.ilemon.ir.IrModule;
import site.ilemon.ir.IrValue;

import java.util.Map;

public final class CModuleEmitter {
    private final CTypeEmitter types = new CTypeEmitter();

    public String emit(IrModule module) {
        StringBuilder out = new StringBuilder();
        out.append("#include <stdbool.h>\n");
        out.append("#include <stdint.h>\n");
        out.append("#include <stddef.h>\n");
        out.append("#include <stdio.h>\n");
        out.append("#include <math.h>\n");
        out.append("#include <limits.h>\n");
        out.append("#include \"lemon_runtime.h\"\n\n");
        out.append("typedef struct { unsigned char _opaque; } lemon_opaque_t;\n\n");

        // Global constants. Private constants are file-scope static; public ones
        // follow the existing export model (compile-time module visibility, so
        // no header/export machinery is needed for a single translation unit).
        for (Map.Entry<String, IrModule.IrConstant> entry : module.constants().entrySet()) {
            IrModule.IrConstant constant = entry.getValue();
            String cType = types.emit(constant.type());
            String qualifier = cType.startsWith("const ") ? "" : "const ";
            String storage = constant.pub() ? "" : "static ";
            out.append(storage).append(qualifier).append(cType).append(' ')
                    .append(CFunctionEmitter.safe(constant.name()))
                    .append(" = ").append(constant.value()).append(";\n");
        }
        if (!module.constants().isEmpty()) {
            out.append('\n');
        }

        // Forward function prototypes
        for (IrFunction function : module.functions()) {
            boolean isMain = "main".equals(function.name());
            String retType = isMain ? "int32_t" : types.emit(function.returnType());
            out.append(retType).append(" ").append(CFunctionEmitter.safe(function.name())).append("(");
            for (int i = 0; i < function.parameters().size(); i++) {
                if (i > 0) out.append(", ");
                IrValue p = function.parameters().get(i);
                out.append(types.emit(p.type())).append(" ").append(CFunctionEmitter.safe(p.name()));
            }
            out.append(");\n");
        }
        out.append("\n");

        CFunctionEmitter functions = new CFunctionEmitter(module.constants().keySet());
        module.functions().forEach(function -> out.append(functions.emit(function)).append('\n'));
        return out.toString();
    }
}
