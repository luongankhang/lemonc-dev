package site.ilemon.semantic;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** Lexical binding scopes. Module objects outlive this manager and are never unloaded here. */
public final class ScopeManager {
    private final Deque<Map<String, ImportSymbol>> scopes = new ArrayDeque<>();

    public ScopeManager() { enterScope(); }

    public void enterScope() { scopes.push(new HashMap<>()); }

    public ImportSymbol declareImport(String name, Path module) {
        Map<String, ImportSymbol> current = scopes.peek();
        if (current.containsKey(name)) throw new IllegalArgumentException("duplicate import binding '" + name + "'");
        ImportSymbol symbol = new ImportSymbol(name, module, scopes.size());
        current.put(name, symbol);
        return symbol;
    }

    public ImportSymbol resolveImport(String name) {
        for (Map<String, ImportSymbol> scope : scopes) {
            ImportSymbol symbol = scope.get(name);
            if (symbol != null) return symbol;
        }
        return null;
    }

    public void exitScope() {
        if (scopes.size() <= 1) throw new IllegalStateException("cannot exit file scope");
        scopes.pop();
    }
}