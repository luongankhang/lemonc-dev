package site.ilemon.backend.c;

import site.ilemon.ir.*;

import java.util.*;

/**
 * Dead store elimination pass for LemonIR.
 * Removes stores to variables that are never read before being overwritten or going out of scope.
 * Currently only eliminates dead CONVERT instructions (type conversions where result is unused).
 */
public final class DeadStoreElimination {

    public void optimize(IrModule module) {
        for (IrFunction function : module.functions()) {
            optimizeFunction(function);
        }
    }

    private void optimizeFunction(IrFunction function) {
        // Compute which variables are actually used in the function
        Set<String> usedVars = computeUsedVariables(function);
        
        // Process each block
        for (BasicBlock block : function.blocks()) {
            optimizeBlock(block, usedVars);
        }
    }

    private void optimizeBlock(BasicBlock block, Set<String> usedVars) {
        List<IrInstruction> instructions = new ArrayList<>(block.instructions());
        List<IrInstruction> optimized = new ArrayList<>();
        
        for (int i = 0; i < instructions.size(); i++) {
            IrInstruction inst = instructions.get(i);
            
            // Only eliminate dead CONVERT instructions where result is never used
            if (inst.op() == IrInstruction.Op.CONVERT && inst.result() != null) {
                String resultName = inst.result().name();
                // Only eliminate if the result variable is never used anywhere in the function
                // and the conversion is not to/from pointer types (which might have side effects)
                if (!usedVars.contains(resultName) && !isPointerConversion(inst)) {
                    // Dead conversion - skip it
                    continue;
                }
            }
            
            optimized.add(inst);
        }
        
        block.setInstructions(optimized);
    }

    private boolean isPointerConversion(IrInstruction inst) {
        if (inst.result() == null || inst.operands().isEmpty()) return false;
        IrType resultType = inst.result().type();
        IrType operandType = inst.operands().get(0).type();
        return resultType.kind() == IrType.Kind.POINTER || operandType.kind() == IrType.Kind.POINTER;
    }

    private Set<String> computeUsedVariables(IrFunction function) {
        Set<String> used = new HashSet<>();
        for (BasicBlock block : function.blocks()) {
            for (IrInstruction inst : block.instructions()) {
                // All operands are used
                for (IrValue op : inst.operands()) {
                    used.add(op.name());
                }
                // Function call targets are "used" in a sense
                if (inst.op() == IrInstruction.Op.CALL || inst.op() == IrInstruction.Op.EXTERNAL_CALL) {
                    if (inst.target() != null) {
                        used.add(inst.target());
                    }
                }
            }
        }
        return used;
    }
}