package site.ilemon.type;

import site.ilemon.ast.Ast;

/** Centralized target-independent rules for the currently supported scalar types. */
public final class TypeRules {
    private TypeRules() {}

    public static boolean isNumeric(Ast.Type.T type) {
        if (type == null) return false;
        return switch (type.getKind()) {
            case BYTE, SHORT, CHAR, INT, LONG, FLOAT, DOUBLE -> true;
            default -> false;
        };
    }

    public static boolean isIntegerLike(Ast.Type.T type) {
        if (type == null) return false;
        return switch (type.getKind()) {
            case BYTE, SHORT, CHAR, INT, LONG -> true;
            default -> false;
        };
    }

    /** C-like integer promotion and usual arithmetic conversion rank for this language subset. */
    public static Ast.Type.T promotedNumericType(Ast.Type.T left, Ast.Type.T right) {
        if (!isNumeric(left) || !isNumeric(right)) return null;
        if (left.getKind() == Ast.Type.TypeKind.DOUBLE || right.getKind() == Ast.Type.TypeKind.DOUBLE) return new Ast.Type.Double();
        if (left.getKind() == Ast.Type.TypeKind.FLOAT || right.getKind() == Ast.Type.TypeKind.FLOAT) return new Ast.Type.Float();
        if (left.getKind() == Ast.Type.TypeKind.LONG || right.getKind() == Ast.Type.TypeKind.LONG) return new Ast.Type.Long();
        return new Ast.Type.Int();
    }
}
