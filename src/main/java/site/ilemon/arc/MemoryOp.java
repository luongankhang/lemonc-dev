package site.ilemon.arc;

public record MemoryOp(Kind kind, String value, int line) {
    public enum Kind { ALLOC, RETAIN, RELEASE, BORROW_LITERAL, SCOPE_EXIT, BOUNDS_CHECK, CALL_ENTER, CALL_EXIT, RETURN }
    public MemoryOp { if (kind == null) throw new IllegalArgumentException("memory operation kind is null"); if (value == null) value = ""; }
    @Override public String toString() { return kind + (value.isEmpty() ? "" : " " + value) + " (line " + line + ")"; }
}
