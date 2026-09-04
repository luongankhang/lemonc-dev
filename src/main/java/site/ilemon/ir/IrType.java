package site.ilemon.ir;

import java.util.Objects;

/** Backend-independent type model for LemonIR. */
public record IrType(Kind kind, IrType elementType, int addressSpace) {
    public enum Kind { BOOL, CHAR, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, ARRAY, STRUCT, ENUM, POINTER, REFERENCE, VOID }
    public IrType {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.ARRAY && elementType == null) throw new IllegalArgumentException("array element type is required");
        if ((kind == Kind.POINTER || kind == Kind.REFERENCE) && elementType == null) throw new IllegalArgumentException("pointee type is required");
        if (addressSpace < 0) throw new IllegalArgumentException("address space cannot be negative");
    }
    public static IrType scalar(Kind kind) { return new IrType(kind, null, 0); }
    public static IrType array(IrType element) { return new IrType(Kind.ARRAY, element, 0); }
    public static IrType pointer(IrType pointee, int addressSpace) { return new IrType(Kind.POINTER, pointee, addressSpace); }
    public static IrType reference(IrType target) { return new IrType(Kind.REFERENCE, target, 0); }
}
