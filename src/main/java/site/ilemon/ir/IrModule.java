package site.ilemon.ir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IrModule {
    /** Backend-neutral global constant: name, declared type, resolved literal text, visibility. */
    public record IrConstant(String name, IrType type, String value, boolean pub) {
        public IrConstant {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("constant name is empty");
            if (type == null) throw new IllegalArgumentException("constant type is null");
            if (value == null) throw new IllegalArgumentException("constant value is null");
        }
    }

    private final String name;
    private final List<IrFunction> functions = new ArrayList<>();
    private final Map<String, IrConstant> constants = new LinkedHashMap<>();

    public IrModule(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("module name is empty");
        this.name = name;
    }

    public String name() { return name; }
    public List<IrFunction> functions() { return List.copyOf(functions); }
    public Map<String, IrConstant> constants() { return Map.copyOf(constants); }
    public IrModule addConstant(IrConstant constant) {
        if (constant == null) throw new IllegalArgumentException("constant is null");
        constants.putIfAbsent(constant.name(), constant);
        return this;
    }
    public IrModule addFunction(IrFunction function) {
        if (function == null) throw new IllegalArgumentException("function is null");
        functions.add(function);
        return this;
    }
}