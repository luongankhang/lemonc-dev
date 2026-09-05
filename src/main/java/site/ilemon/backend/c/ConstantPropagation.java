package site.ilemon.backend.c;

import site.ilemon.ir.*;

import java.util.*;

/**
 * Constant propagation pass for LemonIR.
 * Propagates constant values through the IR to enable further optimizations.
 */
public final class ConstantPropagation {

    public void optimize(IrModule module) {
        for (IrFunction function : module.functions()) {
            optimizeFunction(function);
        }
    }

    private void optimizeFunction(IrFunction function) {
        // Map from variable name to its known constant value
        Map<String, IrValue> constantValues = new HashMap<>();
        
        // Process blocks in order (simple forward pass)
        for (BasicBlock block : function.blocks()) {
            optimizeBlock(block, constantValues);
        }
    }

    private void optimizeBlock(BasicBlock block, Map<String, IrValue> constantValues) {
        List<IrInstruction> instructions = new ArrayList<>(block.instructions());
        List<IrInstruction> optimized = new ArrayList<>();
        
        for (IrInstruction inst : instructions) {
            // Try to substitute operands with known constants
            List<IrValue> newOperands = new ArrayList<>();
            boolean changed = false;
            
            for (IrValue operand : inst.operands()) {
                IrValue constVal = constantValues.get(operand.name());
                if (constVal != null && isConstant(constVal)) {
                    newOperands.add(constVal);
                    changed = true;
                } else {
                    newOperands.add(operand);
                }
            }
            
            IrInstruction newInst = inst;
            if (changed) {
                newInst = new IrInstruction(inst.op(), inst.result(), newOperands, inst.target());
            }
            
            // Track new constant definitions (only explicit CONST instructions)
            if (newInst.op() == IrInstruction.Op.CONST && newInst.result() != null) {
                // Only track if the operand is a literal constant
                if (newInst.operands().size() == 1 && isConstant(newInst.operands().get(0))) {
                    constantValues.put(newInst.result().name(), newInst.operands().get(0));
                }
            }
            
            // NOTE: We do NOT evaluate expressions into new constants here.
            // That would be constant folding, which is a separate optimization.
            // We only do constant propagation: substituting known constant values into operands.
            
            optimized.add(newInst);
        }
        
        block.setInstructions(optimized);
    }

    private boolean isConstant(IrValue value) {
        // Check if the value name looks like a constant (starts with digit or is a known constant pattern)
        String name = value.name();
        // Numeric literals
        if (name.matches("-?\\d+(\\.\\d+)?[fF]?")) return true;
        if ("true".equals(name) || "false".equals(name)) return true;
        if (name.startsWith("\"")) return true; // String literals
        if ("NULL".equals(name) || "null".equals(name)) return true;
        return false;
    }
}