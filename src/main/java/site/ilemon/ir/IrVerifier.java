package site.ilemon.ir;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Structural verifier for backend-independent LemonIR. */
public final class IrVerifier {
    private IrVerifier() {}
    public static void verify(IrModule module) {
        if (module == null) throw invalid("module is null");
        Set<String> functions = new HashSet<>();
        for (IrFunction function : module.functions()) {
            if (function == null) throw invalid("module contains a null function");
            if (!functions.add(function.name())) throw invalid("duplicate function: " + function.name());
            verify(function);
        }
    }
    public static void verify(IrFunction function) {
        if (function == null) throw invalid("function is null");
        if (function.returnType() == null) throw invalid("function has no return type: " + function.name());
        List<BasicBlock> blockList = function.blocksView();
        Set<String> blocks = new HashSet<>(blockList.size() * 2);
        for (BasicBlock block : blockList) {
            if (!blocks.add(block.name())) throw invalid("duplicate block: " + block.name());
        }
        for (BasicBlock block : blockList) {
            List<IrInstruction> instructions = block.instructionsView();
            boolean terminated = false;
            for (IrInstruction instruction : instructions) {
                if (terminated) throw invalid("instruction follows terminator in block: " + block.name());
                if (instruction.result() != null && instruction.op() == IrInstruction.Op.STORE) throw invalid("store cannot produce a result");
                terminated = instruction.isTerminator();
            }
            if (!terminated) throw invalid("unterminated block: " + block.name());
            for (IrInstruction instruction : instructions) {
                if (instruction.isTerminator() && instruction.target() != null && !instruction.target().isBlank()) {
                    if (!blocks.contains(instruction.target())) {
                        throw invalid("branch target '" + instruction.target() + "' in block '" + block.name() + "' is undefined");
                    }
                }
            }
        }
    }
    private static IllegalStateException invalid(String message) { return new IllegalStateException("Invalid LemonIR: " + message); }
}
