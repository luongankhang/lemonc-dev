package site.ilemon.arc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Function-level control flow graph (CFG) and ownership container.
 */
public final class OwnershipFunction {

    private final String name;
    private final boolean returnManaged;
    private final List<String> parameters = new ArrayList<>();
    private final Set<String> managedParameters = new LinkedHashSet<>();
    private final Map<String, String> managedLocals = new LinkedHashMap<>();
    private final List<OwnershipBlock> blocks = new ArrayList<>();
    private OwnershipBlock entryBlock;

    public OwnershipFunction(String name, boolean returnManaged) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("function name is empty");
        }
        this.name = name;
        this.returnManaged = returnManaged;
    }

    public String name() {
        return name;
    }

    public boolean isReturnManaged() {
        return returnManaged;
    }

    public List<String> parameters() {
        return Collections.unmodifiableList(parameters);
    }

    public Set<String> managedParameters() {
        return Collections.unmodifiableSet(managedParameters);
    }

    public Map<String, String> managedLocals() {
        return Collections.unmodifiableMap(managedLocals);
    }

    public List<OwnershipBlock> blocks() {
        return Collections.unmodifiableList(blocks);
    }

    public OwnershipBlock entryBlock() {
        return entryBlock;
    }

    public void setEntryBlock(OwnershipBlock entryBlock) {
        this.entryBlock = entryBlock;
        if (entryBlock != null && !blocks.contains(entryBlock)) {
            blocks.add(entryBlock);
        }
    }

    public OwnershipFunction addBlock(OwnershipBlock block) {
        if (block == null) {
            throw new IllegalArgumentException("block is null");
        }
        if (!blocks.contains(block)) {
            blocks.add(block);
        }
        if (entryBlock == null) {
            entryBlock = block;
        }
        return this;
    }

    public void addParameter(String name, boolean isManaged) {
        parameters.add(name);
        if (isManaged) {
            managedParameters.add(name);
        }
    }

    public void addManagedLocal(String name, String typeDescription) {
        managedLocals.put(name, typeDescription);
    }

    public boolean isManaged(String name) {
        return managedLocals.containsKey(name) || managedParameters.contains(name);
    }

    @Override
    public String toString() {
        return "OwnershipFunction[" + name + ", blocks=" + blocks.size() + ", managedLocals=" + managedLocals.size() + "]";
    }
}
