package site.ilemon.semantic;

import site.ilemon.ast.Ast;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import site.ilemon.exception.SemanticException;
import site.ilemon.diagnostic.Diagnostic;
import site.ilemon.diagnostic.DiagnosticEngine;
import site.ilemon.util.SourceSpan;

/**
 * Method-level variable table with lexical block scoping.
 */
public class MethodVarTable {
    private final Deque<Map<String, Symbol>> scopes = new ArrayDeque<>();
    private final DiagnosticEngine diagnosticEngine;

    public MethodVarTable() {
        this(new DiagnosticEngine());
    }

    public MethodVarTable(DiagnosticEngine diagnosticEngine) {
        this.diagnosticEngine = diagnosticEngine;
        enterScope();
    }

    public void enterScope() {
        scopes.push(new LinkedHashMap<>());
    }

    public void exitScope() {
        if (scopes.size() > 1) {
            scopes.pop();
        }
    }

    public Set<String> currentScopeNames() {
        if (scopes.isEmpty()) return Set.of();
        return Set.copyOf(scopes.peek().keySet());
    }

    public void declare(Ast.Declare.T dec) {
        Ast.Declare.DeclareSingle declareSingle = (Ast.Declare.DeclareSingle) dec;
        if (resolve(declareSingle.getId()) != null) {
            throw duplicate("duplicate variable " + declareSingle.getId()
                    + " at line " + dec.getLineNum(), dec);
        }
        scopes.peek().put(declareSingle.getId(), new Symbol(
                declareSingle.getId(), declareSingle.getType(), Symbol.Kind.LOCAL, dec.getLineNum()));
    }

    public void putFormal(Ast.Declare.T dec) {
        Ast.Declare.DeclareSingle declareSingle = (Ast.Declare.DeclareSingle) dec;
        if (resolve(declareSingle.getId()) != null) {
            throw duplicate("duplicate parameter " + declareSingle.getId()
                    + " at line " + dec.getLineNum(), dec);
        }
        scopes.peek().put(declareSingle.getId(), new Symbol(
                declareSingle.getId(), declareSingle.getType(), Symbol.Kind.PARAMETER, dec.getLineNum()));
    }

    public void put(List<Ast.Declare.T> formals, List<Ast.Declare.T> locals) {
        if (formals != null) {
            for (Ast.Declare.T dec : formals) {
                putFormal(dec);
            }
        }
        if (locals != null) {
            for (Ast.Declare.T dec : locals) {
                declare(dec);
            }
        }
    }

    private SemanticException duplicate(String message, Ast.Declare.T declaration) {
        Diagnostic diagnostic = diagnosticEngine.error(site.ilemon.diagnostic.DiagnosticCodes.SEM_DUPLICATE_DECLARATION)
                .message(message)
                .primary(declaration.getSpan() == null
                        ? SourceSpan.singlePoint(null, 0, Math.max(1, declaration.getLineNum()), 1)
                        : declaration.getSpan(), "duplicate declaration")
                .report();
        return new SemanticException(diagnostic);
    }

    public Ast.Type.T get(String id) {
        Symbol symbol = resolve(id);
        return symbol == null ? null : symbol.getType();
    }

    public Symbol resolve(String id) {
        for (Map<String, Symbol> scope : scopes) {
            Symbol symbol = scope.get(id);
            if (symbol != null) return symbol;
        }
        return null;
    }

    public Set<String> names() {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (Map<String, Symbol> scope : scopes) {
            all.addAll(scope.keySet());
        }
        return all;
    }

    public Ast.Type.T put(String key, Ast.Type.T value) {
        Symbol previous = scopes.peek().put(key, new Symbol(key, value, Symbol.Kind.LOCAL, -1));
        return previous == null ? null : previous.getType();
    }
}
