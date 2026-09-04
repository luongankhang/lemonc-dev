package site.ilemon.backend.c;

import site.ilemon.ir.*;

public final class CFunctionEmitter {
    private final CTypeEmitter types = new CTypeEmitter();
    private final CInstructionEmitter instructions = new CInstructionEmitter();
    public String emit(IrFunction function) {
        StringBuilder out = new StringBuilder(types.emit(function.returnType())).append(' ').append(safe(function.name())).append('(');
        for (int i = 0; i < function.parameters().size(); i++) { if (i > 0) out.append(", "); IrValue p = function.parameters().get(i); out.append(types.emit(p.type())).append(' ').append(safe(p.name())); }
        out.append(") {\n");
        for (BasicBlock block : function.blocks()) { out.append(safe(block.name())).append(":;\n"); for (IrInstruction instruction : block.instructions()) out.append("    ").append(instructions.emit(instruction, types)).append("\n"); }
        return out.append("}\n").toString();
    }
    static String safe(String name) { return name.replaceAll("[^A-Za-z0-9_]", "_"); }
}
