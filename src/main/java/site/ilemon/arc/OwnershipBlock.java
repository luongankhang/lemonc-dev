package site.ilemon.arc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Basic block in the ownership control flow graph (CFG).
 */
public final class OwnershipBlock {

    public enum TerminatorType {
        JUMP,
        BRANCH,
        RETURN,
        UNREACHABLE
    }

    private final String name;
    private final List<MemoryOp> operations = new ArrayList<>();
    private final List<OwnershipBlock> predecessors = new ArrayList<>();
    private final List<OwnershipBlock> successors = new ArrayList<>();
    private TerminatorType terminatorType = TerminatorType.JUMP;

    public OwnershipBlock(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("block name is empty");
        }
        this.name = name;
    }

    public String name() {
        return name;
    }

    public List<MemoryOp> operations() {
        return Collections.unmodifiableList(operations);
    }

    public List<OwnershipBlock> predecessors() {
        return Collections.unmodifiableList(predecessors);
    }

    public List<OwnershipBlock> successors() {
        return Collections.unmodifiableList(successors);
    }

    public TerminatorType terminatorType() {
        return terminatorType;
    }

    public void setTerminatorType(TerminatorType terminatorType) {
        if (terminatorType == null) {
            throw new IllegalArgumentException("terminatorType is null");
        }
        this.terminatorType = terminatorType;
    }

    public OwnershipBlock addOp(MemoryOp operation) {
        if (operation == null) {
            throw new IllegalArgumentException("operation is null");
        }
        operations.add(operation);
        return this;
    }

    public OwnershipBlock addSuccessor(OwnershipBlock successor) {
        if (successor == null) {
            throw new IllegalArgumentException("successor is null");
        }
        if (!successors.contains(successor)) {
            successors.add(successor);
        }
        if (!successor.predecessors.contains(this)) {
            successor.predecessors.add(this);
        }
        return this;
    }

    public boolean isTerminated() {
        return terminatorType == TerminatorType.RETURN || terminatorType == TerminatorType.UNREACHABLE;
    }

    @Override
    public String toString() {
        return "OwnershipBlock[" + name + ", ops=" + operations.size() + ", succs=" + successors.size() + "]";
    }
}
