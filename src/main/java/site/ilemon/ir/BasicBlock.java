package site.ilemon.ir;

import java.util.ArrayList;
import java.util.List;

public final class BasicBlock {
    private final String name;
    private final List<IrInstruction> instructions = new ArrayList<>();
    public BasicBlock(String name) { if (name == null || name.isBlank()) throw new IllegalArgumentException("block name is empty"); this.name = name; }
    public String name() { return name; }
    public List<IrInstruction> instructions() { return List.copyOf(instructions); }
    public BasicBlock add(IrInstruction instruction) { if (instruction == null) throw new IllegalArgumentException("instruction is null"); if (!instructions.isEmpty() && instructions.get(instructions.size() - 1).isTerminator()) throw new IllegalStateException("cannot append after terminator"); instructions.add(instruction); return this; }
    public void setInstructions(List<IrInstruction> newInstructions) {
        instructions.clear();
        instructions.addAll(newInstructions);
    }
}
