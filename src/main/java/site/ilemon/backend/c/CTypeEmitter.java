package site.ilemon.backend.c;

import site.ilemon.ir.IrModule;
import site.ilemon.ir.IrType;

import java.util.Map;

/** Maps target-independent LemonIR types to portable C99 spelling. */
public final class CTypeEmitter {
    /** Struct layouts of the module being emitted; struct kinds map to real C typedefs. */
    private final Map<String, IrModule.IrStruct> structs;
    private final Map<String, IrModule.IrEnum> enums;

    public CTypeEmitter() {
        this(Map.of(), Map.of());
    }

    public CTypeEmitter(Map<String, IrModule.IrStruct> structs) {
        this(structs, Map.of());
    }

    public CTypeEmitter(Map<String, IrModule.IrStruct> structs, Map<String, IrModule.IrEnum> enums) {
        this.structs = structs == null ? Map.of() : structs;
        this.enums = enums == null ? Map.of() : enums;
    }

    /** C tag/typedef spelling for a struct type, e.g. {@code LemonC_Point}. */
    public static String cStructTypeName(String structName) {
        return "LemonC_" + structName;
    }

    /** C tag/typedef spelling for an enum type, e.g. {@code LemonC_Color}. */
    public static String cEnumTypeName(String enumName) {
        return "LemonC_" + enumName.replace('.', '_');
    }

    public static String cEnumMemberName(String enumName, String memberName) {
        return "LemonC_" + enumName.replace('.', '_') + "_" + memberName;
    }

    /** Emits the typedef block for every declared enum. */
    public static String emitEnumTypedefs(Map<String, IrModule.IrEnum> enums) {
        StringBuilder out = new StringBuilder();
        for (IrModule.IrEnum irEnum : enums.values()) {
            out.append("typedef enum ").append(cEnumTypeName(irEnum.name())).append(" {\n");
            for (int i = 0; i < irEnum.members().size(); i++) {
                IrModule.IrEnumMember member = irEnum.members().get(i);
                out.append("    ").append(cEnumMemberName(irEnum.name(), member.name()))
                        .append(" = ").append(member.value());
                if (i < irEnum.members().size() - 1) {
                    out.append(",");
                }
                out.append("\n");
            }
            out.append("} ").append(cEnumTypeName(irEnum.name())).append(";\n");
        }
        return out.toString();
    }

    /** Emits the typedef block for every declared struct (fields embed by value). */
    public static String emitStructTypedefs(Map<String, IrModule.IrStruct> structs, CTypeEmitter emitter) {
        StringBuilder out = new StringBuilder();
        for (IrModule.IrStruct struct : structs.values()) {
            out.append("typedef struct ").append(cStructTypeName(struct.name())).append(" {\n");
            for (IrModule.IrStructField field : struct.fields()) {
                out.append("    ").append(emitter.emit(field.type())).append(' ')
                        .append(field.name()).append(";\n");
            }
            out.append("} ").append(cStructTypeName(struct.name())).append(";\n");
        }
        return out.toString();
    }

    public String emit(IrType type) {
        if (type == null) throw new IllegalArgumentException("IR type is null");
        if (type.kind() == IrType.Kind.STRUCT && structs.containsKey(type.name())) {
            return cStructTypeName(type.name());
        }
        if (type.kind() == IrType.Kind.ENUM) {
            return type.name() != null ? cEnumTypeName(type.name()) : "int32_t";
        }
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
            case ARRAY -> "lemon_array*";
            case POINTER -> emit(type.elementType()) + "*";
            case REFERENCE -> emit(type.elementType()) + "*";
            case STRUCT -> "lemon_opaque_t";
            case ENUM -> "int32_t";
        };
    }

    public String emitElement(IrType type) {
        if (type == null || type.elementType() == null) {
            return "void";
        }
        return emit(type.elementType());
    }
}
