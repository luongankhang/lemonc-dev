package site.ilemon.ir;

import java.util.ArrayList;
import java.util.List;

public final class IrModule {
    private final String name;
    private final List<IrFunction> functions = new ArrayList<>();
    public IrModule(String name) { if (name == null || name.isBlank()) throw new IllegalArgumentException("module name is empty"); this.name = name; }
    public String name() { return name; }
    public List<IrFunction> functions() { return List.copyOf(functions); }
    public IrModule addFunction(IrFunction function) { if (function == null) throw new IllegalArgumentException("function is null"); functions.add(function); return this; }
}
