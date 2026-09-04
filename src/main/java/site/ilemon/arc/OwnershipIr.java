package site.ilemon.arc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Top-level ownership intermediate representation containing function CFGs and memory operations.
 */
public final class OwnershipIr {

    private final List<MemoryOp> operations = new ArrayList<>();
    private final List<OwnershipFunction> functions = new ArrayList<>();

    public void add(MemoryOp operation) {
        if (operation == null) {
            throw new IllegalArgumentException("operation is null");
        }
        operations.add(operation);
    }

    public void addFunction(OwnershipFunction function) {
        if (function == null) {
            throw new IllegalArgumentException("function is null");
        }
        functions.add(function);
    }

    public List<OwnershipFunction> functions() {
        return Collections.unmodifiableList(functions);
    }

    public List<MemoryOp> operations() {
        if (!functions.isEmpty() && operations.isEmpty()) {
            List<MemoryOp> allOps = new ArrayList<>();
            for (OwnershipFunction function : functions) {
                for (OwnershipBlock block : function.blocks()) {
                    allOps.addAll(block.operations());
                }
            }
            return Collections.unmodifiableList(allOps);
        }
        return Collections.unmodifiableList(operations);
    }
}
