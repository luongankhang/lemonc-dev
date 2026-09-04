package site.ilemon.arc;

import site.ilemon.util.SourceSpan;

public record MemoryOp(Kind kind, String value, int line, SourceSpan span) {
    public enum Kind {
        ALLOC,
        RETAIN,
        RELEASE,
        BORROW_LITERAL,
        SCOPE_EXIT,
        BOUNDS_CHECK,
        CALL_ENTER,
        CALL_EXIT,
        RETURN,
        STORE,
        LOAD,
        MOVE,
        TRANSFER
    }

    public MemoryOp {
        if (kind == null) throw new IllegalArgumentException("memory operation kind is null");
        if (value == null) value = "";
    }

    public MemoryOp(Kind kind, String value, int line) {
        this(kind, value, line, null);
    }

    public MemoryOp(Kind kind, String value, SourceSpan span) {
        this(kind, value, span != null ? span.getStartLine() : 0, span);
    }

    @Override
    public String toString() {
        return kind + (value.isEmpty() ? "" : " " + value) + " (line " + line + ")";
    }
}
