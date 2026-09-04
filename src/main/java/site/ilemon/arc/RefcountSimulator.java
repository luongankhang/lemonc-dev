package site.ilemon.arc;

import java.util.HashMap;
import java.util.Map;

/** Compile/test-time shadow checker; it never emits target code. */
public final class RefcountSimulator {
    public void verify(OwnershipIr ir) {
        Map<String, Integer> counts = new HashMap<>();
        for (MemoryOp op : ir.operations()) {
            if (op.kind() == MemoryOp.Kind.ALLOC) counts.put(op.value().split(":", 2)[0], 1);
            else if (op.kind() == MemoryOp.Kind.RETAIN) counts.computeIfPresent(op.value(), (k, v) -> v + 1);
            else if (op.kind() == MemoryOp.Kind.RELEASE) { Integer count = counts.get(op.value()); if (count == null || count == 0) throw new IllegalStateException("release without ownership: " + op.value()); if (count == 1) counts.remove(op.value()); else counts.put(op.value(), count - 1); }
        }
        if (!counts.isEmpty()) throw new IllegalStateException("unreleased ownership: " + counts.keySet());
    }
}
