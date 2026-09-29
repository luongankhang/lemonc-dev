package site.ilemon.ir;

import java.util.*;
import java.util.stream.Collectors;

/**
 * ARC optimization pass: eliminates redundant retain/release operations.
 * 
 * Optimizations performed:
 * 1. Self-assignment elimination: x = x should not generate retain/release
 * 2. Redundant retain/release pairs: retain(x); release(x) with no intervening use
 * 3. Dead store elimination: store to a variable that is never read before overwrite/exit
 */
public final class ArcOptimizer {

    public void optimize(IrModule module) {
        for (IrFunction function : module.functions()) {
            optimizeFunction(function);
        }
    }

    private void optimizeFunction(IrFunction function) {
        // Build use-def chains and liveness information
        LivenessInfo liveness = computeLiveness(function);
        
        // Process each block
        for (BasicBlock block : function.blocksView()) {
            optimizeBlock(block, liveness);
        }
        
        // Remove empty blocks
        function.removeEmptyBlocks();
    }

    private record FieldLoadRecord(IrInstruction inst, String base, String field, String tempName) {}
    private record RetainRecord(int index, IrInstruction retainInst, FieldLoadRecord loadRec) {}

    private void optimizeBlock(BasicBlock block, LivenessInfo liveness) {
        List<IrInstruction> instructions = block.instructionsView();
        Set<IrInstruction> toRemove = Collections.newSetFromMap(new IdentityHashMap<>());

        // Pass 1: Intra-block field load tracking & retain/release cancellation
        Map<String, FieldLoadRecord> activeLoads = new HashMap<>();
        Map<String, RetainRecord> pendingFieldRetains = new HashMap<>();

        for (int i = 0; i < instructions.size(); i++) {
            IrInstruction inst = instructions.get(i);

            // Self-copy elimination: STRUCT_COPY x, [x]
            if (inst.op() == IrInstruction.Op.STRUCT_COPY && inst.result() != null
                    && inst.operands().size() == 1
                    && inst.result().name().equals(inst.operands().get(0).name())) {
                toRemove.add(inst);
                continue;
            }

            // Track FIELD_LOAD
            if (inst.op() == IrInstruction.Op.FIELD_LOAD && inst.result() != null && inst.operands().size() == 1) {
                String temp = inst.result().name();
                String base = inst.operands().get(0).name();
                String field = inst.target() != null ? inst.target() : "";
                activeLoads.put(temp, new FieldLoadRecord(inst, base, field, temp));
            }

            // Check if this instruction modifies any base
            Set<String> modifiedBases = getModifiedBases(inst);
            for (String modified : modifiedBases) {
                activeLoads.entrySet().removeIf(e -> e.getValue().base().equals(modified));
                pendingFieldRetains.entrySet().removeIf(e -> e.getValue().loadRec().base().equals(modified));
            }

            // If an external call passes an address containing base, invalidate base
            if (inst.op() == IrInstruction.Op.EXTERNAL_CALL && !"lemon_retain".equals(inst.target()) && !"lemon_release".equals(inst.target())) {
                for (IrValue op : inst.operands()) {
                    String opName = op.name();
                    activeLoads.entrySet().removeIf(e -> opName.contains(e.getValue().base()));
                    pendingFieldRetains.entrySet().removeIf(e -> opName.contains(e.getValue().loadRec().base()));
                }
            }

            if (isRetain(inst) && inst.operands().size() == 1) {
                String operandName = inst.operands().get(0).name();
                FieldLoadRecord loadRec = activeLoads.get(operandName);
                if (loadRec != null) {
                    String key = loadRec.base() + "." + loadRec.field();
                    pendingFieldRetains.put(key, new RetainRecord(i, inst, loadRec));
                }
            } else if (isRelease(inst) && inst.operands().size() == 1) {
                String operandName = inst.operands().get(0).name();
                FieldLoadRecord loadRec = activeLoads.get(operandName);
                if (loadRec != null) {
                    String key = loadRec.base() + "." + loadRec.field();
                    RetainRecord retainRec = pendingFieldRetains.remove(key);
                    if (retainRec != null) {
                        String retainTemp = retainRec.loadRec().tempName();
                        String releaseTemp = loadRec.tempName();
                        if (retainTemp.equals(releaseTemp)) {
                            // Same temp used for retain and release
                            if (countOperandUsesInBlock(instructions, retainTemp) == 2) {
                                toRemove.add(retainRec.retainInst());
                                toRemove.add(inst);
                                toRemove.add(retainRec.loadRec().inst());
                            }
                        } else {
                            // Two different temps loaded from same base.field
                            int retainUses = countOperandUsesInBlock(instructions, retainTemp);
                            int releaseUses = countOperandUsesInBlock(instructions, releaseTemp);
                            if (retainUses == 1 && releaseUses == 1) {
                                toRemove.add(retainRec.retainInst());
                                toRemove.add(inst);
                                toRemove.add(retainRec.loadRec().inst());
                                toRemove.add(loadRec.inst());
                            }
                        }
                    }
                }
            }
        }

        List<IrInstruction> optimized = new ArrayList<>(instructions.size());
        
        for (int i = 0; i < instructions.size(); i++) {
            IrInstruction inst = instructions.get(i);
            if (toRemove.contains(inst)) {
                continue;
            }
            
            // Optimization 1: Self-assignment retain/release elimination
            // Pattern: retain(x); release(x) where x is the same variable
            if (isRetain(inst) && i + 1 < instructions.size()) {
                IrInstruction next = instructions.get(i + 1);
                if (isRelease(next) && sameOperand(inst, next)) {
                    // Check if the operand is used between retain and release
                    if (!isOperandUsedBetween(instructions, i, inst.operands().get(0))) {
                        // Skip both instructions
                        i++; // Skip the release too
                        continue;
                    }
                }
            }
            
            // Optimization 2: Self-assignment pattern in managed assignments
            // Pattern: retain(x); release(x); CONVERT x = x
            if (isRetain(inst) && i + 2 < instructions.size()) {
                IrInstruction releaseInst = instructions.get(i + 1);
                IrInstruction convertInst = instructions.get(i + 2);
                if (isRelease(releaseInst) && isConvert(convertInst)
                        && sameOperand(inst, releaseInst)
                        && sameResultOperand(convertInst, inst)) {
                    // This is x = x pattern, skip all three
                    i += 2;
                    continue;
                }
            }
            
            // Optimization 3: Dead store elimination
            // If this is a CONVERT/STORE to a managed variable that is dead (not live-out)
            if ((isConvert(inst) || isStore(inst)) && inst.result() != null) {
                String resultName = inst.result().name();
                if (liveness.isDeadAt(resultName, block, i)) {
                    // Check if it's a managed type and the store is the only side effect
                    if (inst.result().type().kind() == IrType.Kind.ARRAY) {
                        // Skip this store if the value is not used
                        // But we must be careful: if there are side effects in the RHS, we can't skip
                        // For now, only skip if RHS is a simple variable/constant
                        if (isSimpleValue(inst.operands())) {
                            continue;
                        }
                    }
                }
            }
            
            optimized.add(inst);
        }
        
        // Replace block instructions
        block.setInstructions(optimized);
    }

    private Set<String> getModifiedBases(IrInstruction inst) {
        Set<String> modified = new HashSet<>();
        if (inst.op() == IrInstruction.Op.FIELD_STORE && !inst.operands().isEmpty()) {
            modified.add(inst.operands().get(0).name());
        } else if (inst.op() == IrInstruction.Op.STRUCT_ZERO && inst.result() != null) {
            modified.add(inst.result().name());
        } else if (inst.op() == IrInstruction.Op.STRUCT_COPY && inst.result() != null) {
            modified.add(inst.result().name());
        } else if ((inst.op() == IrInstruction.Op.CONVERT || inst.op() == IrInstruction.Op.STORE) && inst.result() != null) {
            modified.add(inst.result().name());
        }
        return modified;
    }

    private int countOperandUsesInBlock(List<IrInstruction> instructions, String varName) {
        int count = 0;
        for (IrInstruction inst : instructions) {
            for (IrValue op : inst.operands()) {
                if (op.name().equals(varName)) {
                    count++;
                }
            }
        }
        return count;
    }

    private boolean isRetain(IrInstruction inst) {
        return inst.op() == IrInstruction.Op.EXTERNAL_CALL 
                && "lemon_retain".equals(inst.target());
    }

    private boolean isRelease(IrInstruction inst) {
        return inst.op() == IrInstruction.Op.EXTERNAL_CALL 
                && "lemon_release".equals(inst.target());
    }

    private boolean isConvert(IrInstruction inst) {
        return inst.op() == IrInstruction.Op.CONVERT;
    }

    private boolean isStore(IrInstruction inst) {
        return inst.op() == IrInstruction.Op.STORE;
    }

    private boolean sameOperand(IrInstruction inst1, IrInstruction inst2) {
        if (inst1.operands().size() != 1 || inst2.operands().size() != 1) return false;
        return inst1.operands().get(0).name().equals(inst2.operands().get(0).name());
    }

    private boolean sameResultOperand(IrInstruction convert, IrInstruction retain) {
        return convert.result() != null 
                && retain.operands().size() == 1
                && convert.result().name().equals(retain.operands().get(0).name());
    }

    private boolean isOperandUsedBetween(List<IrInstruction> instructions, int startIdx, IrValue operand) {
        String opName = operand.name();
        for (int i = startIdx + 1; i < instructions.size(); i++) {
            IrInstruction inst = instructions.get(i);
            // Check if operand is used as an operand in any instruction
            for (IrValue op : inst.operands()) {
                if (op.name().equals(opName)) {
                    return true;
                }
            }
            // Check if it's the result being overwritten (which counts as use for liveness)
            if (inst.result() != null && inst.result().name().equals(opName)) {
                return true;
            }
            // Stop at terminators
            if (inst.isTerminator()) break;
        }
        return false;
    }

    private boolean isSimpleValue(List<IrValue> operands) {
        // Simple values: constants, variables (not results of computations)
        for (IrValue op : operands) {
            String name = op.name();
            // Temp values (_t*) are results of computations
            if (name.startsWith("_t")) return false;
            // Array accesses and other complex operations would have been lowered to temps
        }
        return true;
    }

    /**
     * Computes liveness information for variables in the function.
     * A variable is live at a point if its value may be used along some path
     * from that point to the exit.
     */
    private LivenessInfo computeLiveness(IrFunction function) {
        // Map from block -> (variable -> live-in/live-out)
        
        // Initialize
        List<BasicBlock> blockList = function.blocksView();
        Map<BasicBlock, Set<String>> liveIn = new HashMap<>();
        Map<BasicBlock, Set<String>> liveOut = new HashMap<>();
        for (int i = 0; i < blockList.size(); i++) {
            BasicBlock block = blockList.get(i);
            liveIn.put(block, new HashSet<>());
            liveOut.put(block, new HashSet<>());
        }
        
        // Build successor map (name lookups via a one-time name -> block map)
        Map<BasicBlock, List<BasicBlock>> successors = new HashMap<>();
        Map<String, BasicBlock> blocksByName = new HashMap<>();
        for (int i = 0; i < blockList.size(); i++) {
            BasicBlock block = blockList.get(i);
            successors.put(block, new ArrayList<>());
            blocksByName.put(block.name(), block);
        }
        for (BasicBlock block : blockList) {
            for (IrInstruction inst : block.instructionsView()) {
                if (inst.op() == IrInstruction.Op.BRANCH || inst.op() == IrInstruction.Op.COND_BRANCH) {
                    if (inst.target() != null) {
                        BasicBlock target = blocksByName.get(inst.target());
                        if (target != null) {
                            successors.get(block).add(target);
                        }
                    }
                }
            }
        }
        
        // Iterative dataflow analysis. The traversal order is fixed (it depends
        // only on the CFG shape), so compute it once instead of every round.
        List<BasicBlock> reversePostOrder = getReversePostOrder(function);
        boolean changed = true;
        while (changed) {
            changed = false;
            
            for (BasicBlock block : reversePostOrder) {
                // liveOut = union of liveIn of successors
                Set<String> newLiveOut = new HashSet<>();
                for (BasicBlock succ : successors.get(block)) {
                    newLiveOut.addAll(liveIn.get(succ));
                }
                
                // liveIn = (liveOut - killed) U used
                Set<String> killed = getKilledVariables(block);
                Set<String> used = getUsedVariables(block);
                
                Set<String> newLiveIn = new HashSet<>(newLiveOut);
                newLiveIn.removeAll(killed);
                newLiveIn.addAll(used);
                
                if (!newLiveOut.equals(liveOut.get(block))) {
                    liveOut.put(block, newLiveOut);
                    changed = true;
                }
                if (!newLiveIn.equals(liveIn.get(block))) {
                    liveIn.put(block, newLiveIn);
                    changed = true;
                }
            }
        }
        
        return new LivenessInfo(liveIn, liveOut);
    }

    private Set<String> getKilledVariables(BasicBlock block) {
        Set<String> killed = new HashSet<>();
        for (IrInstruction inst : block.instructionsView()) {
            if (inst.result() != null) {
                killed.add(inst.result().name());
            }
        }
        return killed;
    }

    private Set<String> getUsedVariables(BasicBlock block) {
        Set<String> used = new HashSet<>();
        for (IrInstruction inst : block.instructionsView()) {
            for (IrValue op : inst.operands()) {
                used.add(op.name());
            }
        }
        return used;
    }

    private List<BasicBlock> getReversePostOrder(IrFunction function) {
        // The CFG is a straight-line list in this IR; blocks are visited in
        // list order (matches the original DFS behavior on this shape) with no
        // per-call copies.
        List<BasicBlock> blockList = function.blocksView();
        List<BasicBlock> order = new ArrayList<>(blockList);
        Collections.reverse(order);
        return order;
    }

    private record LivenessInfo(
            Map<BasicBlock, Set<String>> liveIn,
            Map<BasicBlock, Set<String>> liveOut
    ) {
        boolean isDeadAt(String varName, BasicBlock block, int instructionIndex) {
            // A variable is dead at an instruction if it's not in liveOut of the block
            // or if it's killed by a later instruction in the same block before any use
            Set<String> liveOutSet = liveOut.get(block);
            if (liveOutSet == null || !liveOutSet.contains(varName)) {
                return true;
            }
            // Check if there's a use after this instruction in the same block
            List<IrInstruction> instructions = block.instructionsView();
            for (int i = instructionIndex + 1; i < instructions.size(); i++) {
                IrInstruction inst = instructions.get(i);
                for (IrValue op : inst.operands()) {
                    if (op.name().equals(varName)) {
                        return false; // Used later in this block
                    }
                }
                if (inst.result() != null && inst.result().name().equals(varName)) {
                    return false; // Redefined (killed) - but actually this means dead before this
                }
                if (inst.isTerminator()) break;
            }
            // Check liveOut of block
            return !liveOutSet.contains(varName);
        }
    }
}