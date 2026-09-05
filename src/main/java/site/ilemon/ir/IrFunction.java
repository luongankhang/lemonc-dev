package site.ilemon.ir;

import java.util.ArrayList;
import java.util.List;

public final class IrFunction {
    private final String name;
    private final IrType returnType;
    private final List<IrValue> parameters;
    private final List<BasicBlock> blocks = new ArrayList<>();
    public IrFunction(String name, IrType returnType, List<IrValue> parameters) { if (name == null || name.isBlank()) throw new IllegalArgumentException("function name is empty"); this.name = name; this.returnType = returnType; this.parameters = List.copyOf(parameters == null ? List.of() : parameters); }
    public String name() { return name; }
    public IrType returnType() { return returnType; }
    public List<IrValue> parameters() { return parameters; }
    public List<BasicBlock> blocks() { return List.copyOf(blocks); }
    public IrFunction addBlock(BasicBlock block) { if (block == null) throw new IllegalArgumentException("block is null"); blocks.add(block); return this; }
    public void removeEmptyBlocks() {
        blocks.removeIf(b -> b.instructions().isEmpty());
    }
}
