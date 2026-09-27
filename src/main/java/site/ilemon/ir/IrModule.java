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
    /** Declared struct layouts, keyed by struct name, in declaration order. */
    private final Map<String, IrStruct> structs = new LinkedHashMap<>();

    /** Backend-neutral struct layout: name plus ordered typed fields. */
    public record IrStruct(String name, List<IrStructField> fields) {
        public IrStruct {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("struct name is empty");
            fields = List.copyOf(fields == null ? List.of() : fields);
        }
    }

    public record IrStructField(String name, IrType type) {
        public IrStructField {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("field name is empty");
            if (type == null) throw new IllegalArgumentException("field type is null");
        }
    }

    /** Registers a struct layout; the first declaration of a name wins. */
    public IrModule addStruct(IrStruct struct) {
        if (struct == null) throw new IllegalArgumentException("struct is null");
        structs.putIfAbsent(struct.name(), struct);
        return this;
    }

    public IrStruct struct(String name) {
        return structs.get(name);
    }

    public boolean hasNoStructs() {
        return structs.isEmpty();
    }

    public Map<String, IrStruct> structsView() {
        return structs;
    }

    public IrModule(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("module name is empty");
        this.name = name;
    }

    public String name() { return name; }
    public List<IrFunction> functions() { return List.copyOf(functions); }
    public Map<String, IrConstant> constants() { return Map.copyOf(constants); }

    /** Live view of the constants table; callers must not mutate it.
     * Avoids a full map copy on every lookup in the backend hot loops. */
    public Map<String, IrConstant> constantsView() { return constants; }

    /** True when the module declares no constants. */
    public boolean hasNoConstants() { return constants.isEmpty(); }
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