package site.ilemon.ir;

import java.util.HashSet;
import java.util.Set;

/** Structural verifier for backend-independent LemonIR. */
public final class IrVerifier {
    private IrVerifier() {}
    public static void verify(IrModule module) {
        if (module == null) throw invalid("module is null");
        Set<String> functions = new HashSet<>();
        for (IrFunction function : module.functions()) {
            if (!functions.add(function.name())) throw invalid("duplicate function: " + function.name());
            verify(function);
        }
    }
    public static void verify(IrFunction function) {
        if (function.returnType() == null) throw invalid("function has no return type: " + function.name());
        Set<String> blocks = new HashSet<>();
        for (BasicBlock block : function.blocks()) {
            if (!blocks.add(block.name())) throw invalid("duplicate block: " + block.name());
            boolean terminated = false;
            for (IrInstruction instruction : block.instructions()) {
                if (terminated) throw invalid("instruction follows terminator in block: " + block.name());
                if (instruction.result() != null && instruction.op() == IrInstruction.Op.STORE) throw invalid("store cannot produce a result");
                terminated = instruction.isTerminator();
            }
            if (!terminated) throw invalid("unterminated block: " + block.name());
        }
    }
    private static IllegalStateException invalid(String message) { return new IllegalStateException("Invalid LemonIR: " + message); }
}
