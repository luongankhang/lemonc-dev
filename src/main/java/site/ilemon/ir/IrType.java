package site.ilemon.ir;

import java.util.Objects;

/**
 * Backend-independent type model for LemonIR.
 *
 * <p>STRUCT values are nominal: {@code name} carries the declared struct
 * identity ({@code name} is null for every non-struct type), and backends
 * resolve the name through the module's struct table.</p>
 */
public record IrType(Kind kind, IrType elementType, int addressSpace, String name) {
    public enum Kind { BOOL, CHAR, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, STRING, ARRAY, STRUCT, ENUM, POINTER, REFERENCE, VOID }
    public IrType {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.ARRAY && elementType == null) throw new IllegalArgumentException("array element type is required");
        if ((kind == Kind.POINTER || kind == Kind.REFERENCE) && elementType == null) throw new IllegalArgumentException("pointee type is required");
        if (kind == Kind.STRUCT && (name == null || name.isBlank())) throw new IllegalArgumentException("struct type requires a name");
        if (kind != Kind.STRUCT && name != null) throw new IllegalArgumentException("only struct types carry a name");
        if (addressSpace < 0) throw new IllegalArgumentException("address space cannot be negative");
    }
    public static IrType scalar(Kind kind) { return new IrType(kind, null, 0, null); }
    public static IrType array(IrType element) { return new IrType(Kind.ARRAY, element, 0, null); }
    public static IrType pointer(IrType pointee, int addressSpace) { return new IrType(Kind.POINTER, pointee, addressSpace, null); }
    public static IrType reference(IrType target) { return new IrType(Kind.REFERENCE, target, 0, null); }
    /** Named struct value type. */
    public static IrType structType(String name) { return new IrType(Kind.STRUCT, null, 0, name); }
}
