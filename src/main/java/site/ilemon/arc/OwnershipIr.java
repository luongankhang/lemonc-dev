package site.ilemon.arc;

import java.util.ArrayList;
import java.util.List;

public final class OwnershipIr {
    private final List<MemoryOp> operations = new ArrayList<>();
    public void add(MemoryOp operation) { if (operation == null) throw new IllegalArgumentException("operation is null"); operations.add(operation); }
    public List<MemoryOp> operations() { return List.copyOf(operations); }
}
