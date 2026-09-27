package site.ilemon.backend.c;

import site.ilemon.ir.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class CFunctionEmitter {
    private final CTypeEmitter types = new CTypeEmitter();
    private final CInstructionEmitter instructions;

    public CFunctionEmitter() {
        this(java.util.Set.of());
    }

    public CFunctionEmitter(java.util.Set<String> constNames) {
        this.instructions = new CInstructionEmitter(constNames);
    }

    public String emit(IrFunction function) {
        StringBuilder out = new StringBuilder();
        boolean isMain = "main".equals(function.name());
        String retType = isMain ? "int32_t" : types.emit(function.returnType());
        out.append(retType).append(' ').append(safe(function.name())).append('(');

        for (int i = 0; i < function.parameters().size(); i++) {
            if (i > 0) out.append(", ");
            IrValue p = function.parameters().get(i);
            out.append(types.emit(p.type())).append(' ').append(safe(p.name()));
        }
        out.append(") {\n");

        // Compute which variables are actually used (read) in the function
        Set<String> usedVars = computeUsedVariables(function);

        // Hoist all instruction results as local variable declarations
        Set<String> paramNames = function.parameters().stream().map(IrValue::name).collect(Collectors.toSet());
        Map<String, IrType> locals = new LinkedHashMap<>();
        for (BasicBlock block : function.blocks()) {
            for (IrInstruction inst : block.instructions()) {
                if (inst.result() != null) {
                    String rname = inst.result().name();
                    if (!paramNames.contains(rname)) {
                        locals.putIfAbsent(rname, inst.result().type());
                    }
                }
            }
        }

        for (Map.Entry<String, IrType> entry : locals.entrySet()) {
            String cType = types.emit(entry.getValue());
            out.append("    ").append(cType).append(" ").append(safe(entry.getKey())).append(" = 0;\n");
            // Suppress -Wunused-but-set-variable for variables written but never read
            if (!usedVars.contains(entry.getKey())) {
                out.append("    (void)").append(safe(entry.getKey())).append(";\n");
            }
        }

        // Add (void) casts for unused parameters to suppress -Wunused-parameter
        for (IrValue p : function.parameters()) {
            if (!usedVars.contains(p.name())) {
                out.append("    (void)").append(safe(p.name())).append(";\n");
            }
        }

        // Collect used labels to avoid -Wunused-label errors with -Wall -Wextra -Werror
        Set<String> usedLabels = new java.util.HashSet<>();
        for (BasicBlock block : function.blocks()) {
            for (IrInstruction inst : block.instructions()) {
                if ((inst.op() == IrInstruction.Op.BRANCH || inst.op() == IrInstruction.Op.COND_BRANCH)
                        && inst.target() != null && !inst.target().isBlank()) {
                    usedLabels.add(inst.target());
                }
            }
        }

        boolean lastIsReturn = false;
        for (BasicBlock block : function.blocks()) {
            if (usedLabels.contains(block.name())) {
                out.append(safe(block.name())).append(":;\n");
            }
            for (IrInstruction instruction : block.instructions()) {
                if (isMain && instruction.op() == IrInstruction.Op.RETURN && instruction.operands().isEmpty()) {
                    out.append("    return 0;\n");
                    lastIsReturn = true;
                } else {
                    out.append("    ").append(instructions.emit(instruction, types)).append("\n");
                    if (instruction.op() == IrInstruction.Op.RETURN) {
                        lastIsReturn = true;
                    } else {
                        lastIsReturn = false;
                    }
                }
            }
        }

        if (isMain && !lastIsReturn) {
            out.append("    return 0;\n");
        }

        out.append("}\n");
        return out.toString();
    }

    private Set<String> computeUsedVariables(IrFunction function) {
        Set<String> used = new java.util.HashSet<>();
        for (BasicBlock block : function.blocks()) {
            for (IrInstruction inst : block.instructions()) {
                // All operands are used (read)
                for (IrValue op : inst.operands()) {
                    used.add(op.name());
                }
                // External call targets like printf use their arguments
                if (inst.op() == IrInstruction.Op.EXTERNAL_CALL && "printf".equals(inst.target())) {
                    for (IrValue op : inst.operands()) {
                        used.add(op.name());
                    }
                }
                // Function call results that are stored in variables are used if the variable is used
                // But we only track operands here
            }
        }
        return used;
    }

    public static String safe(String name) {
        if (name == null || name.isBlank()) return "_unnamed";
        boolean needsUnderscore = Character.isDigit(name.charAt(0));
        boolean hasInvalid = false;
        int len = name.length();
        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_')) {
                hasInvalid = true;
                break;
            }
        }
        if (!needsUnderscore && !hasInvalid) {
            return name;
        }
        StringBuilder sb = new StringBuilder(len + (needsUnderscore ? 1 : 0));
        if (needsUnderscore) {
            sb.append('_');
        }
        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString();
    }
}
