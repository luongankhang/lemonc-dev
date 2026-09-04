package site.ilemon.backend.c;

import site.ilemon.ir.IrType;

/** Maps target-independent LemonIR types to portable C99 spelling. */
public final class CTypeEmitter {
    public String emit(IrType type) {
        if (type == null) throw new IllegalArgumentException("IR type is null");
        return switch (type.kind()) {
            case BOOL -> "bool";
            case CHAR -> "uint16_t";
            case BYTE -> "int8_t";
            case SHORT -> "int16_t";
            case INT -> "int32_t";
            case LONG -> "int64_t";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case VOID -> "void";
            case STRING -> "const char*";
            case ARRAY -> emit(type.elementType()) + "*";
            case POINTER -> emit(type.elementType()) + "*";
            case REFERENCE -> emit(type.elementType()) + "*";
            case STRUCT, ENUM -> "lemon_opaque_t";
        };
    }
}
