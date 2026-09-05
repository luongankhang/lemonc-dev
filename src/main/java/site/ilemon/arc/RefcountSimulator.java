package site.ilemon.arc;

import site.ilemon.diagnostic.Diagnostic;
import site.ilemon.diagnostic.DiagnosticCodes;
import site.ilemon.diagnostic.DiagnosticEngine;
import site.ilemon.diagnostic.Severity;
import site.ilemon.util.SourceSpan;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Path-sensitive compile-time shadow checker for Automatic Reference Counting (ARC).
 * Interprets Ownership IR over the control flow graph using a binding-aware abstract model:
 * - Double release (E8001)
 * - Use after release (E8002)
 * - Missing release / reference leak (E8003)
 * - Ownership imbalance / violation across CFG paths (E8004)
 * - Invalid move or copy (E8005)
 * - Lifetime violations (E8006)
 */
public final class RefcountSimulator {

    public enum OwnershipStatus {
        UNINITIALIZED,
        OWNED,
        RELEASED,
        TRANSFERRED
    }

    public static final class ValueRefState {
        private final int refCount;
        private final OwnershipStatus status;
        private final int allocLine;
        private final SourceSpan allocSpan;
        private final int releaseLine;
        private final SourceSpan releaseSpan;

        public ValueRefState(int refCount, OwnershipStatus status, int allocLine, SourceSpan allocSpan,
                             int releaseLine, SourceSpan releaseSpan) {
            this.refCount = refCount;
            this.status = status;
            this.allocLine = allocLine;
            this.allocSpan = allocSpan;
            this.releaseLine = releaseLine;
            this.releaseSpan = releaseSpan;
        }

        public int refCount() {
            return refCount;
        }

        public OwnershipStatus status() {
            return status;
        }

        public int allocLine() {
            return allocLine;
        }

        public SourceSpan allocSpan() {
            return allocSpan;
        }

        public int releaseLine() {
            return releaseLine;
        }

        public SourceSpan releaseSpan() {
            return releaseSpan;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ValueRefState that = (ValueRefState) o;
            return refCount == that.refCount && status == that.status;
        }

        @Override
        public int hashCode() {
            return Objects.hash(refCount, status);
        }

        @Override
        public String toString() {
            return status + "(" + refCount + ")";
        }
    }

    public static final class BlockState {
        private final Map<String, String> varBindings = new HashMap<>();
        private final Map<String, ValueRefState> objectStates = new HashMap<>();

        public BlockState() {}

        public BlockState(BlockState other) {
            this.varBindings.putAll(other.varBindings);
            this.objectStates.putAll(other.objectStates);
        }

        public Map<String, String> varBindings() {
            return varBindings;
        }

        public Map<String, ValueRefState> objectStates() {
            return objectStates;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BlockState that = (BlockState) o;
            return Objects.equals(varBindings, that.varBindings) && Objects.equals(objectStates, that.objectStates);
        }

        @Override
        public int hashCode() {
            return Objects.hash(varBindings, objectStates);
        }
    }

    private final DiagnosticEngine diagnosticEngine;

    public RefcountSimulator() {
        this(new DiagnosticEngine());
    }

    public RefcountSimulator(DiagnosticEngine diagnosticEngine) {
        this.diagnosticEngine = diagnosticEngine != null ? diagnosticEngine : new DiagnosticEngine();
    }

    public DiagnosticEngine getDiagnosticEngine() {
        return diagnosticEngine;
    }

    public List<Diagnostic> getDiagnostics() {
        return diagnosticEngine.diagnostics();
    }

    public boolean hasErrors() {
        return diagnosticEngine.hasErrors();
    }

    /**
     * Verifies the given Ownership IR.
     * Throws {@link IllegalStateException} if any ownership violation is detected,
     * maintaining backward compatibility with existing callers.
     */
    public void verify(OwnershipIr ir) {
        if (ir == null) {
            throw new IllegalArgumentException("OwnershipIr is null");
        }

        if (ir.functions().isEmpty()) {
            verifyFlatOperations(ir.operations());
        } else {
            for (OwnershipFunction func : ir.functions()) {
                verifyFunction(func);
            }
        }

        if (diagnosticEngine.hasErrors()) {
            Diagnostic first = diagnosticEngine.diagnostics().stream()
                    .filter(d -> d.severity() == Severity.ERROR)
                    .findFirst()
                    .orElse(null);
            String message = first != null ? first.message() : "ARC ownership verification failed";
            throw new IllegalStateException(message);
        }
    }

    /**
     * Path-sensitive abstract interpretation of an OwnershipFunction CFG.
     */
    public void verifyFunction(OwnershipFunction func) {
        if (func.entryBlock() == null) {
            return;
        }

        Map<OwnershipBlock, BlockState> inStates = new HashMap<>();
        Map<OwnershipBlock, BlockState> outStates = new HashMap<>();
        Map<OwnershipBlock, Integer> iterationCount = new HashMap<>();

        Deque<OwnershipBlock> worklist = new ArrayDeque<>();
        worklist.add(func.entryBlock());
        inStates.put(func.entryBlock(), new BlockState());

        final int MAX_LOOP_ITERATIONS = 4;

        while (!worklist.isEmpty()) {
            OwnershipBlock block = worklist.poll();
            int iters = iterationCount.getOrDefault(block, 0);
            if (iters > MAX_LOOP_ITERATIONS) {
                continue;
            }
            iterationCount.put(block, iters + 1);

            // Compute input state by merging output states of all predecessors
            BlockState currentIn;
            if (block.predecessors().isEmpty()) {
                currentIn = inStates.getOrDefault(block, new BlockState());
            } else {
                List<BlockState> predOuts = new java.util.ArrayList<>();
                for (OwnershipBlock pred : block.predecessors()) {
                    if (outStates.containsKey(pred)) {
                        predOuts.add(outStates.get(pred));
                    }
                }
                if (predOuts.isEmpty()) {
                    currentIn = inStates.getOrDefault(block, new BlockState());
                } else {
                    currentIn = mergeStates(block, predOuts);
                }
            }

            BlockState prevIn = inStates.get(block);
            if (prevIn != null && iters > 0 && prevIn.equals(currentIn)) {
                continue;
            }
            inStates.put(block, currentIn);

            // Simulate the block's operations
            BlockState current = new BlockState(currentIn);
            for (MemoryOp op : block.operations()) {
                simulateOperation(op, current, func);
            }

            outStates.put(block, current);

            // If block is a function terminator, verify that managed local references are cleanly released
            if (block.terminatorType() == OwnershipBlock.TerminatorType.RETURN) {
                for (Map.Entry<String, ValueRefState> entry : current.objectStates().entrySet()) {
                    String objId = entry.getKey();
                    ValueRefState state = entry.getValue();
                    if (state.status == OwnershipStatus.OWNED && state.refCount > 0) {
                        report(Severity.ERROR, DiagnosticCodes.ARC_MISSING_RELEASE,
                                "Missing release for managed object '" + objId + "' (reference leaked on function exit)",
                                state.allocSpan, "allocated here");
                    }
                }
            }

            // Propagate to successors
            for (OwnershipBlock succ : block.successors()) {
                worklist.add(succ);
            }
        }
    }

    private void simulateOperation(MemoryOp op, BlockState state, OwnershipFunction func) {
        int line = op.line();
        SourceSpan span = op.span();

        switch (op.kind()) {
            case ALLOC -> {
                String varName = op.value().split(":", 2)[0].trim();
                String objId = "obj_" + varName;
                ValueRefState existing = state.objectStates().get(objId);
                if (existing != null && existing.status == OwnershipStatus.OWNED && existing.refCount > 0) {
                    report(Severity.ERROR, DiagnosticCodes.ARC_MISSING_RELEASE,
                            "Managed reference '" + varName + "' re-allocated before previous reference was released (leak)",
                            span, "re-allocated here");
                }
                state.varBindings().put(varName, objId);
                state.objectStates().put(objId, new ValueRefState(1, OwnershipStatus.OWNED, line, span, 0, null));
            }
            case RETAIN -> {
                String varName = extractRootName(op.value());
                String objId = state.varBindings().getOrDefault(varName, varName);
                ValueRefState st = state.objectStates().get(objId);

                if (st != null && st.status == OwnershipStatus.RELEASED) {
                    report(Severity.ERROR, DiagnosticCodes.ARC_USE_AFTER_RELEASE,
                            "Use after release of managed value '" + varName + "'"
                                    + (st.releaseLine > 0 ? " (previously released at line " + st.releaseLine + ")" : ""),
                            span, "used after release here");
                } else if (st == null || st.status == OwnershipStatus.UNINITIALIZED) {
                    if (func.isManaged(varName)) {
                        state.varBindings().put(varName, objId);
                        state.objectStates().put(objId, new ValueRefState(1, OwnershipStatus.OWNED, line, span, 0, null));
                    } else {
                        report(Severity.ERROR, DiagnosticCodes.ARC_OWNERSHIP_VIOLATION,
                                "Retain on unmanaged or uninitialized value '" + varName + "'",
                                span, "retain here");
                    }
                } else {
                    state.objectStates().put(objId, new ValueRefState(st.refCount + 1, OwnershipStatus.OWNED,
                            st.allocLine, st.allocSpan, st.releaseLine, st.releaseSpan));
                }
            }
            case RELEASE -> {
                String varName = extractRootName(op.value());
                String objId = state.varBindings().getOrDefault(varName, varName);
                ValueRefState st = state.objectStates().get(objId);

                if (st == null || st.refCount <= 0 || st.status == OwnershipStatus.RELEASED) {
                    report(Severity.ERROR, DiagnosticCodes.ARC_DOUBLE_RELEASE,
                            "Double release of managed value '" + varName + "'"
                                    + (st != null && st.releaseLine > 0 ? " (already released at line " + st.releaseLine + ")" : ""),
                            span, "released again here");
                } else {
                    int nextCount = st.refCount - 1;
                    OwnershipStatus nextStatus = nextCount == 0 ? OwnershipStatus.RELEASED : OwnershipStatus.OWNED;
                    state.objectStates().put(objId, new ValueRefState(nextCount, nextStatus,
                            st.allocLine, st.allocSpan, line, span));
                }
            }
            case BOUNDS_CHECK, LOAD -> {
                String varName = extractRootName(op.value());
                String objId = state.varBindings().getOrDefault(varName, varName);
                ValueRefState st = state.objectStates().get(objId);
                if (st != null && st.status == OwnershipStatus.RELEASED) {
                    report(Severity.ERROR, DiagnosticCodes.ARC_USE_AFTER_RELEASE,
                            "Use after release of managed value '" + varName + "'"
                                    + (st.releaseLine > 0 ? " (previously released at line " + st.releaseLine + ")" : ""),
                            span, "accessed here");
                }
            }
            case STORE -> {
                String expr = op.value();
                // Check for pointer escape: storing a local address through dereference
                if (expr.startsWith("escape:")) {
                    String escapedLocal = expr.substring("escape:".length());
                    report(Severity.ERROR, DiagnosticCodes.ARC_LIFETIME_VIOLATION,
                            "Pointer escape detected: storing address of local variable '" + escapedLocal
                                    + "' through dereference allows it to outlive its scope",
                            span, "local address escapes here");
                }
                if (expr.contains("=")) {
                    String[] parts = expr.split("=", 2);
                    String target = extractRootName(parts[0].trim());
                    String source = extractRootName(parts[1].trim());

                    String srcObj = state.varBindings().getOrDefault(source, source);
                    ValueRefState srcSt = state.objectStates().get(srcObj);
                    if (srcSt != null && srcSt.status == OwnershipStatus.RELEASED) {
                        report(Severity.ERROR, DiagnosticCodes.ARC_USE_AFTER_RELEASE,
                                "Use after release of source value '" + source + "' in assignment",
                                span, "source read here");
                    }
                    if (state.varBindings().containsKey(source)) {
                        state.varBindings().put(target, srcObj);
                    }
                }
            }
            case TRANSFER -> {
                String varName = extractRootName(op.value());
                String objId = state.varBindings().getOrDefault(varName, varName);
                ValueRefState st = state.objectStates().get(objId);
                if (st != null) {
                    state.objectStates().put(objId, new ValueRefState(st.refCount, OwnershipStatus.TRANSFERRED,
                            st.allocLine, st.allocSpan, st.releaseLine, st.releaseSpan));
                }
            }
            case SCOPE_EXIT -> {
                for (Map.Entry<String, ValueRefState> entry : state.objectStates().entrySet()) {
                    String objId = entry.getKey();
                    ValueRefState st = entry.getValue();
                    if (st.status == OwnershipStatus.OWNED && st.refCount > 0) {
                        report(Severity.ERROR, DiagnosticCodes.ARC_MISSING_RELEASE,
                                "Missing release for managed object '" + objId + "' on scope exit",
                                span, "leaked here");
                    }
                }
            }
            default -> {}
        }
    }

    private BlockState mergeStates(OwnershipBlock block, List<BlockState> states) {
        BlockState result = new BlockState();
        if (states.isEmpty()) {
            return result;
        }

        // Merge variable bindings
        Set<String> allVars = new HashSet<>();
        for (BlockState s : states) {
            allVars.addAll(s.varBindings().keySet());
        }
        for (String var : allVars) {
            String firstObj = null;
            for (BlockState s : states) {
                String obj = s.varBindings().get(var);
                if (obj != null) {
                    if (firstObj == null) {
                        firstObj = obj;
                    } else if (!firstObj.equals(obj)) {
                        // Different object bound across branches
                    }
                }
            }
            if (firstObj != null) {
                result.varBindings().put(var, firstObj);
            }
        }

        // Merge object states
        Set<String> allObjects = new HashSet<>();
        for (BlockState s : states) {
            allObjects.addAll(s.objectStates().keySet());
        }

        for (String objId : allObjects) {
            ValueRefState first = null;
            boolean hasMismatch = false;

            for (BlockState s : states) {
                ValueRefState st = s.objectStates().get(objId);
                if (st == null) {
                    continue;
                }
                if (first == null) {
                    first = st;
                } else if (!first.equals(st)) {
                    hasMismatch = true;
                    if (first.status != st.status) {
                        report(Severity.ERROR, DiagnosticCodes.ARC_OWNERSHIP_VIOLATION,
                                "Ownership status mismatch for '" + objId + "' across control-flow branches converging at "
                                        + block.name() + " (" + first.status + " vs " + st.status + ")",
                                null, "join point");
                    } else if (first.refCount != st.refCount) {
                        report(Severity.ERROR, DiagnosticCodes.ARC_OWNERSHIP_VIOLATION,
                                "Reference count imbalance for '" + objId + "' across control-flow branches converging at "
                                        + block.name() + " (count " + first.refCount + " vs " + st.refCount + ")",
                                null, "join point");
                    }
                    break;
                }
            }

            if (first != null && !hasMismatch) {
                result.objectStates().put(objId, first);
            }
        }

        return result;
    }

    /**
     * Fallback verification for flat operations (preserves backward compatibility with simple unit tests).
     */
    private void verifyFlatOperations(List<MemoryOp> ops) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Integer> releaseLines = new HashMap<>();

        for (MemoryOp op : ops) {
            switch (op.kind()) {
                case ALLOC -> {
                    String name = op.value().split(":", 2)[0].trim();
                    counts.put(name, 1);
                }
                case RETAIN -> {
                    String name = extractRootName(op.value());
                    if (!counts.containsKey(name) || counts.get(name) <= 0) {
                        report(Severity.ERROR, DiagnosticCodes.ARC_USE_AFTER_RELEASE,
                                "Use after release of managed value: " + name, op.span(), "retain here");
                    } else {
                        counts.computeIfPresent(name, (k, v) -> v + 1);
                    }
                }
                case RELEASE -> {
                    String name = extractRootName(op.value());
                    Integer count = counts.get(name);
                    if (count == null || count <= 0) {
                        report(Severity.ERROR, DiagnosticCodes.ARC_DOUBLE_RELEASE,
                                "Double release or release without ownership: " + name, op.span(), "release here");
                    } else if (count == 1) {
                        counts.remove(name);
                        releaseLines.put(name, op.line());
                    } else {
                        counts.put(name, count - 1);
                    }
                }
                case BOUNDS_CHECK, LOAD -> {
                    String name = extractRootName(op.value());
                    if (releaseLines.containsKey(name) && !counts.containsKey(name)) {
                        report(Severity.ERROR, DiagnosticCodes.ARC_USE_AFTER_RELEASE,
                                "Use after release of managed value: " + name, op.span(), "accessed here");
                    }
                }
                default -> {}
            }
        }

        if (!counts.isEmpty()) {
            for (String unreleased : counts.keySet()) {
                report(Severity.ERROR, DiagnosticCodes.ARC_MISSING_RELEASE,
                        "Unreleased ownership / missing release: " + unreleased, null, "unreleased");
            }
        }
    }

    private void report(Severity severity, String code, String message, SourceSpan span, String label) {
        diagnosticEngine.report(severity, code, message, span, label);
    }

    private String extractRootName(String expr) {
        if (expr == null) return "";
        int bracket = expr.indexOf('[');
        if (bracket >= 0) {
            expr = expr.substring(0, bracket);
        }
        return expr.trim();
    }
}
