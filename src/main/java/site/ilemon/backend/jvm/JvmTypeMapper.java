package site.ilemon.backend.jvm;

import site.ilemon.ir.IrType;

/**
 * Central LemonIR → JVM type mapping.
 *
 * <p>JVM representation rules enforced here:</p>
 * <ul>
 *   <li>{@code byte}/{@code short}/{@code char}/{@code bool}/{@code int} share one
 *       operand-stack slot and use {@code int} instructions; their JVM descriptors
 *       stay distinct ({@code B}/{@code S}/{@code C}/{@code I}).</li>
 *   <li>{@code long}/{@code double} occupy two local slots / stack slots.</li>
 *   <li>{@code bool} scalars map to descriptor {@code I} (compatible with the legacy
 *       JVM output), while {@code bool[]} is the JVM {@code [Z} array type.</li>
 *   <li>{@code string} is {@code java/lang/String}; arrays map to JVM array descriptors.</li>
 * </ul>
 */
final class JvmTypeMapper {

    /** JVM field/method descriptor for an IR type. */
    String descriptor(IrType type) {
        if (type == null) {
            throw new IllegalArgumentException("cannot map a null IR type");
        }
        return switch (type.kind()) {
            case BOOL -> "I";
            case BYTE -> "B";
            case SHORT -> "S";
            case CHAR -> "C";
            case INT -> "I";
            case LONG -> "J";
            case FLOAT -> "F";
            case DOUBLE -> "D";
            case VOID -> "V";
            case STRING -> "Ljava/lang/String;";
            // bool arrays are JVM boolean[] ([Z) even though scalar bool maps
            // to I; array creation, element access and descriptors must agree.
            case ARRAY -> "[" + arrayElementDescriptor(type.elementType());
            // A pointer value is the reference to a single-element cell whose
            // element is the pointee value; pointer descriptors therefore are
            // array descriptors of the cell element type. This gives pointers
            // an explicit, type-tagged JVM representation (never a raw JVM
            // reference exposed as a "pointer").
            case POINTER, REFERENCE -> "[" + cellElementDescriptor(type.elementType());
            default -> throw new IllegalArgumentException(
                    "no JVM descriptor for LemonIR type " + type.kind());
        };
    }

    private String arrayElementDescriptor(IrType elementType) {
        if (elementType.kind() == IrType.Kind.BOOL) {
            return "Z";
        }
        if (elementType.kind() == IrType.Kind.ARRAY) {
            return "[" + arrayElementDescriptor(elementType.elementType());
        }
        return descriptor(elementType);
    }

    /** Element type of a pointer cell: bool cells are JVM boolean[]. */
    private String cellElementDescriptor(IrType elementType) {
        if (elementType.kind() == IrType.Kind.BOOL) {
            return "Z";
        }
        if (elementType.kind() == IrType.Kind.ARRAY) {
            return "[" + arrayElementDescriptor(elementType.elementType());
        }
        return descriptor(elementType); // recursion handles pointer-of-pointer
    }

    /** Number of JVM local/operand-stack slots for a value of this type. */
    int slots(IrType type) {
        return switch (type.kind()) {
            case LONG, DOUBLE -> 2;
            case ARRAY, STRING, BOOL, BYTE, SHORT, CHAR, INT, FLOAT -> 1;
            case POINTER, REFERENCE -> 1;
            default -> 0;
        };
    }

    /** True for types represented as JVM {@code int} values on the stack. */
    boolean isIntFamily(IrType type) {
        return switch (type.kind()) {
            case BOOL, BYTE, SHORT, CHAR, INT -> true;
            default -> false;
        };
    }

    /** True for the two-slot numeric types. */
    boolean isWide(IrType type) {
        return type.kind() == IrType.Kind.LONG || type.kind() == IrType.Kind.DOUBLE;
    }
}