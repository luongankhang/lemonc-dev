package site.ilemon.compiler;

import site.ilemon.ast.Ast;
import site.ilemon.diagnostic.Diagnostic;
import site.ilemon.diagnostic.DiagnosticCodes;
import site.ilemon.diagnostic.DiagnosticEngine;
import site.ilemon.exception.CompilerException;
import site.ilemon.exception.ParseException;
import site.ilemon.lexer.Lexer;
import site.ilemon.parser.Parser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Loads Lemon modules as AST units; imported source is never textually included. */
public final class ModuleLoader {
    private final Map<Path, Ast.MainClass.MainClassSingle> cache = new HashMap<>();
    private final Set<Path> loading = new HashSet<>();

    public void resolve(Ast.Program.T program, Path sourcePath) throws IOException {
        Ast.MainClass.MainClassSingle main = (Ast.MainClass.MainClassSingle) ((Ast.Program.ProgramSingle) program).getMainClass();
        Path normalizedOwner = sourcePath.toAbsolutePath().normalize();
        for (Ast.StructDecl s : main.getStructs()) {
            s.setDeclaringModule(normalizedOwner.toString());
        }
        for (Ast.EnumDecl e : main.getEnums()) {
            e.setDeclaringModule(normalizedOwner.toString());
        }
        for (Ast.Method.T m : main.getMethods()) {
            if (m instanceof Ast.Method.MethodSingle method) {
                method.setDeclaringModule(normalizedOwner.toString());
            }
        }
        loadImports(main, normalizedOwner);
        // Propagate nested-module structs, enums, constants to the root module
        // so that deep-copied methods can resolve aliases correctly.
        propagateNestedImports(main, normalizedOwner);
    }

    /**
     * Walks every module in the loaded dependency graph and merges their
     * struct/enum/constant/declaration sets into the root {@code root}.
     */
    @SuppressWarnings("unchecked")
    private void propagateNestedImports(Ast.MainClass.MainClassSingle root, Path rootPath) throws IOException {
        java.util.Set<Path> visited = new HashSet<>();
        java.util.Deque<Path> queue = new java.util.ArrayDeque<>();
        // Use the parent directory of the root source file for resolving imports
        Path rootDir = rootPath.getParent() != null ? rootPath.getParent() : rootPath;
        collectNestedModulesFrom(root, root, rootDir, queue, visited);
    }

    private void collectNestedModulesFrom(Ast.MainClass.MainClassSingle root,
                                           Ast.MainClass.MainClassSingle current,
                                           Path currentPath,
                                           java.util.Deque<Path> queue,
                                           java.util.Set<Path> visited) throws IOException {
        // currentPath is the DIRECTORY containing the current module, not the file itself
        Path currentDir = currentPath;
        if (currentPath.toFile().isFile()) {
            currentDir = currentPath.getParent() != null ? currentPath.getParent() : currentPath;
        }
        for (Ast.ImportDecl imp : current.getImports()) {
            Path p = currentDir.resolve(imp.getPath()).normalize().toAbsolutePath();
            if (!visited.add(p)) continue;
            Ast.MainClass.MainClassSingle imported = load(p);
            // Merge struct declarations (avoid duplicates)
            for (Ast.StructDecl s : imported.getStructs()) {
                boolean dup = false;
                for (Ast.StructDecl existing : root.getStructs()) {
                    if (existing.getName().equals(s.getName())) { dup = true; break; }
                }
                if (!dup) root.getStructs().add(s);
            }
            // Merge enum declarations (avoid duplicates)
            for (Ast.EnumDecl e : imported.getEnums()) {
                boolean dup = false;
                for (Ast.EnumDecl existing : root.getEnums()) {
                    if (existing.getName().equals(e.getName())) { dup = true; break; }
                }
                if (!dup) root.getEnums().add(e);
            }
            // Merge moduleStructs entries (avoid duplicates per alias)
            for (Map.Entry<String, ArrayList<Ast.StructDecl>> entry : imported.getModuleStructs().entrySet()) {
                root.getModuleStructs().computeIfAbsent(entry.getKey(), k -> new ArrayList<>());
                ArrayList<Ast.StructDecl> target = root.getModuleStructs().get(entry.getKey());
                for (Ast.StructDecl s : entry.getValue()) {
                    boolean dup = false;
                    for (Ast.StructDecl existing : target) {
                        if (existing.getName().equals(s.getName())) { dup = true; break; }
                    }
                    if (!dup) target.add(s);
                }
            }
            // Merge moduleEnums entries (avoid duplicates per alias)
            for (Map.Entry<String, ArrayList<Ast.EnumDecl>> entry : imported.getModuleEnums().entrySet()) {
                root.getModuleEnums().computeIfAbsent(entry.getKey(), k -> new ArrayList<>());
                ArrayList<Ast.EnumDecl> target = root.getModuleEnums().get(entry.getKey());
                for (Ast.EnumDecl e : entry.getValue()) {
                    boolean dup = false;
                    for (Ast.EnumDecl existing : target) {
                        if (existing.getName().equals(e.getName())) { dup = true; break; }
                    }
                    if (!dup) target.add(e);
                }
            }
            // Merge constants (avoid duplicates by id)
            for (Ast.ConstDecl c : imported.getConstants()) {
                boolean dup = false;
                for (Ast.ConstDecl existing : root.getConstants()) {
                    if (existing.getId().equals(c.getId())) { dup = true; break; }
                }
                if (!dup) root.getConstants().add(c);
            }
            // Recurse
            collectNestedModulesFrom(root, imported, p, queue, visited);
        }
    }

    private void loadImports(Ast.MainClass.MainClassSingle owner, Path ownerPath) throws IOException {
        Map<String, Path> aliases = new HashMap<>();
        for (Ast.ImportDecl importDecl : owner.getImports()) {
            loadImport(owner, ownerPath, importDecl, aliases);
        }
        for (Ast.Method.T method : new java.util.ArrayList<>(owner.getMethods())) {
            collectStatementImports(method instanceof Ast.Method.MethodSingle m ? m.getStms() : null, owner, ownerPath, aliases);
        }
    }

    private void collectStatementImports(java.util.List<Ast.Stmt.T> statements, Ast.MainClass.MainClassSingle owner,
                                          Path ownerPath, Map<String, Path> aliases) throws IOException {
        if (statements == null) return;
        for (Ast.Stmt.T statement : statements) {
            if (statement instanceof Ast.Stmt.Import importStmt) {
                loadImport(owner, ownerPath, importStmt.getDeclaration(), aliases);
            } else if (statement instanceof Ast.Stmt.Block block) {
                collectStatementImports(block.getStmts(), owner, ownerPath, aliases);
            } else if (statement instanceof Ast.Stmt.If branch) {
                collectStatementImports(java.util.List.of(branch.getThenStmt()), owner, ownerPath, aliases);
                if (branch.getElseStmt() != null) collectStatementImports(java.util.List.of(branch.getElseStmt()), owner, ownerPath, aliases);
            } else if (statement instanceof Ast.Stmt.While loop) {
                collectStatementImports(java.util.List.of(loop.getBody()), owner, ownerPath, aliases);
            } else if (statement instanceof Ast.Stmt.For loop) {
                collectStatementImports(java.util.List.of(loop.getBody()), owner, ownerPath, aliases);
            } else if (statement instanceof Ast.Stmt.Switch switchStmt) {
                if (switchStmt.getClauses() != null) {
                    for (Ast.Stmt.CaseClause clause : switchStmt.getClauses()) {
                        if (clause.getBody() != null) {
                            collectStatementImports(clause.getBody(), owner, ownerPath, aliases);
                        }
                    }
                }
            }
        }
    }

    private void loadImport(Ast.MainClass.MainClassSingle owner, Path ownerPath, Ast.ImportDecl importDecl,
                            Map<String, Path> aliases) throws IOException {
        Path importedPath = ownerPath.getParent().resolve(importDecl.getPath()).normalize().toAbsolutePath();
        if (!Files.isRegularFile(importedPath)) {
            throw moduleError("module not found: " + importDecl.getPath(), importDecl.getSpan());
        }
        Path previous = aliases.putIfAbsent(importDecl.getName(), importedPath);
        if (previous != null) {
            if (!previous.equals(importedPath)) {
                throw moduleError("duplicate module import '" + importDecl.getName() + "'", importDecl.getSpan());
            }
            return;
        }
        Ast.MainClass.MainClassSingle imported = load(importedPath);

        // Build a map of original method names to prefixed names for this import.
        // This is used to rewrite internal calls within the imported module's methods.
        Map<String, String> callRewriteMap = new HashMap<>();
        for (Ast.Method.T methodNode : imported.getMethods()) {
            Ast.Method.MethodSingle method = (Ast.Method.MethodSingle) methodNode;
            if (method.getVisibility() == Ast.Visibility.PUBLIC && !"main".equals(method.getId())) {
                String originalName = method.getId();
                String prefixedName = importDecl.getName() + "_" + originalName;
                callRewriteMap.put(originalName, prefixedName);
            }
        }

        Set<String> existing = new HashSet<>();
        for (Ast.Method.T node : owner.getMethods()) existing.add(((Ast.Method.MethodSingle) node).getId());
        for (Ast.Method.T methodNode : imported.getMethods()) {
            Ast.Method.MethodSingle method = (Ast.Method.MethodSingle) methodNode;
            if (method.getVisibility() == Ast.Visibility.PUBLIC && !"main".equals(method.getId())) {
                String exportedName = importDecl.getName() + "_" + method.getId();
                if (existing.add(exportedName)) {
                    // Deep copy the method node to avoid mutating shared objects
                    // that may be referenced by other imports at different levels.
                    Ast.Method.MethodSingle exported = deepCopyMethod(method);
                    exported.setId(exportedName);
                    exported.setDeclaringModule(importedPath.toString());
                    // Rewrite internal calls to use prefixed names (e.g., mul -> math_mul)
                    new MethodCallRewriter(callRewriteMap).rewrite(exported);
                    // The exported body may reference its declaring module's
                    // constants (including private ones), so keep that table.
                    exported.setModuleConsts(imported.getConstants());
                    owner.getMethods().add(exported);
                }
            }
        }
        // Re-export public constants as alias_NAME so the importing module can
        // read them through module-qualified syntax (math.VERSION -> math_VERSION).
        // Private constants are not copied and therefore stay invisible.
        for (Ast.ConstDecl constDecl : imported.getConstants()) {
            if (constDecl.getVisibility() == Ast.Visibility.PUBLIC) {
                String exportedName = importDecl.getName() + "_" + constDecl.getId();
                if (!existingConst(owner).contains(exportedName)) {
                    Ast.ConstDecl exported = new Ast.ConstDecl(
                            constDecl.getType(), exportedName, constDecl.getInitializer(),
                            constDecl.getVisibility(), constDecl.getLineNum());
                    exported.setSpan(constDecl.getSpan());
                    owner.getConstants().add(exported);
                }
            }
        }

        // Track all structs in the imported module for alias lookups and visibility enforcement
        ArrayList<Ast.StructDecl> modStructs = owner.getModuleStructs().computeIfAbsent(importDecl.getName(), k -> new ArrayList<>());
        for (Ast.StructDecl s : imported.getStructs()) {
            s.setDeclaringModule(importedPath.toString());
            boolean found = false;
            for (Ast.StructDecl existingS : modStructs) {
                if (existingS.getName().equals(s.getName())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                modStructs.add(s);
            }
        }

        // Re-export all structs into owner so they are available for code generation and
        // declaring-module internal usage, preserving their visibility and declaringModule
        // for semantic access checks.
        for (Ast.StructDecl structDecl : imported.getStructs()) {
            if (!existingStruct(owner).contains(structDecl.getName())) {
                Ast.StructDecl exported = new Ast.StructDecl(
                        structDecl.getName(), structDecl.getFields(), structDecl.getVisibility(), structDecl.getLineNum());
                exported.setSpan(structDecl.getSpan());
                exported.setDeclaringModule(importedPath.toString());
                owner.getStructs().add(exported);
            }
        }

        // Re-export public enum members as constants so importing module can read them
        for (Ast.EnumDecl enumDecl : imported.getEnums()) {
            if (enumDecl.getVisibility() == Ast.Visibility.PUBLIC) {
                for (Ast.EnumMember m : enumDecl.getMembers()) {
                    String exported1 = importDecl.getName() + "_" + m.getName();
                    if (!existingConst(owner).contains(exported1)) {
                        Ast.Type.Enum enumType = new Ast.Type.Enum(importDecl.getName() + "." + enumDecl.getName());
                        Ast.ConstDecl c1 = new Ast.ConstDecl(
                                enumType,
                                exported1,
                                new Ast.Expr.Number(enumType, m.getValue(), m.getLineNum()),
                                Ast.Visibility.PUBLIC, m.getLineNum());
                        c1.setResolvedValue(String.valueOf(m.getValue()));
                        c1.setSpan(m.getSpan());
                        owner.getConstants().add(c1);
                    }
                    String exported2 = importDecl.getName() + "_" + enumDecl.getName() + "_" + m.getName();
                    if (!existingConst(owner).contains(exported2)) {
                        Ast.Type.Enum enumType = new Ast.Type.Enum(importDecl.getName() + "." + enumDecl.getName());
                        Ast.ConstDecl c2 = new Ast.ConstDecl(
                                enumType,
                                exported2,
                                new Ast.Expr.Number(enumType, m.getValue(), m.getLineNum()),
                                Ast.Visibility.PUBLIC, m.getLineNum());
                        c2.setResolvedValue(String.valueOf(m.getValue()));
                        c2.setSpan(m.getSpan());
                        owner.getConstants().add(c2);
                    }
                }
            }
        }

        // Track all enums in the imported module for alias lookups and visibility enforcement
        ArrayList<Ast.EnumDecl> modEnums = owner.getModuleEnums().computeIfAbsent(importDecl.getName(), k -> new ArrayList<>());
        for (Ast.EnumDecl e : imported.getEnums()) {
            e.setDeclaringModule(importedPath.toString());
            boolean found = false;
            for (Ast.EnumDecl existingE : modEnums) {
                if (existingE.getName().equals(e.getName())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                modEnums.add(e);
            }
        }

        // Re-export all enums into owner so they are available for code generation
        for (Ast.EnumDecl enumDecl : imported.getEnums()) {
            if (!existingEnum(owner).contains(enumDecl.getName())) {
                Ast.EnumDecl exported = new Ast.EnumDecl(
                        enumDecl.getName(), enumDecl.getMembers(), enumDecl.getVisibility(), enumDecl.getLineNum());
                exported.setSpan(enumDecl.getSpan());
                exported.setDeclaringModule(importedPath.toString());
                owner.getEnums().add(exported);
            }
        }
    }

    private static Set<String> existingEnum(Ast.MainClass.MainClassSingle owner) {
        Set<String> names = new HashSet<>();
        for (Ast.EnumDecl enumDecl : owner.getEnums()) {
            names.add(enumDecl.getName());
        }
        return names;
    }

    private static Set<String> existingStruct(Ast.MainClass.MainClassSingle owner) {
        Set<String> names = new HashSet<>();
        for (Ast.StructDecl structDecl : owner.getStructs()) {
            names.add(structDecl.getName());
        }
        return names;
    }

    private static Set<String> existingConst(Ast.MainClass.MainClassSingle owner) {
        Set<String> names = new HashSet<>();
        for (Ast.ConstDecl constDecl : owner.getConstants()) {
            names.add(constDecl.getId());
        }
        return names;
    }

    private Ast.MainClass.MainClassSingle load(Path path) throws IOException {
        if (!loading.add(path)) {
            throw moduleError("circular module dependency detected: " + path, null);
        }
        Ast.MainClass.MainClassSingle cached = cache.get(path);
        if (cached != null) {
            loading.remove(path);
            return cached;
        }
        try {
            Lexer lexer = new Lexer(path.toFile());
            Parser parser = new Parser(lexer);
            Ast.Program.T program;
            try {
                program = parser.parse();
            } catch (ParseException e) {
                throw e;
            }
            Ast.MainClass.MainClassSingle module = (Ast.MainClass.MainClassSingle) ((Ast.Program.ProgramSingle) program).getMainClass();
            Path normalized = path.toAbsolutePath().normalize();
            for (Ast.StructDecl s : module.getStructs()) {
                s.setDeclaringModule(normalized.toString());
            }
            for (Ast.EnumDecl e : module.getEnums()) {
                e.setDeclaringModule(normalized.toString());
            }
            for (Ast.Method.T m : module.getMethods()) {
                if (m instanceof Ast.Method.MethodSingle method) {
                    method.setDeclaringModule(normalized.toString());
                }
            }
            cache.put(path, module);
            loadImports(module, path);
            return module;
        } finally {
            loading.remove(path);
        }
    }

    private CompilerException moduleError(String message, site.ilemon.util.SourceSpan span) {
        DiagnosticEngine engine = new DiagnosticEngine();
        Diagnostic diagnostic = engine.error(DiagnosticCodes.MODULE_NOT_FOUND)
                .message(message)
                .primary(span, "module import")
                .report();
        return new CompilerException(diagnostic);
    }

    /**
     * Deep-copies a method node so that mutating its id does not affect the cached
     * source module or other imports at different nesting levels.
     */
    @SuppressWarnings("unchecked")
    private static Ast.Method.MethodSingle deepCopyMethod(Ast.Method.MethodSingle src) {
        Ast.Type.T copyRetType = deepCopyType(src.getRetType());
        ArrayList<Ast.Declare.T> copyFormals = src.getFormals() != null
                ? copyDeclareList(src.getFormals()) : new ArrayList<>();
        ArrayList<Ast.Declare.T> copyLocals = src.getLocals() != null
                ? copyDeclareList(src.getLocals()) : new ArrayList<>();
        ArrayList<Ast.Stmt.T> copyStms = src.getStms() != null
                ? copyStmtList(src.getStms()) : new ArrayList<>();
        Ast.Stmt.T copyRetExp = src.getRetExp() != null
                ? (Ast.Stmt.T) deepCopyStmt(src.getRetExp()) : null;

        Ast.Method.MethodSingle copy = new Ast.Method.MethodSingle(
                copyRetType, src.getId(), copyFormals, copyLocals, copyStms, copyRetExp, src.getLineNum());
        copy.setVisibility(src.getVisibility());
        copy.setDeclaringModule(src.getDeclaringModule());
        // Will be set by caller after this returns
        return copy;
    }

    /**
     * Copies source spans from every node in {@code src} to its deep copy in {@code dst}.
     * This must be called after deep-copy + rewrite so that diagnostics reference the
     * correct source file and line/column positions.
     */
    private static void copySpan(Ast.Method.MethodSingle src, Ast.Method.MethodSingle dst) {
        dst.setSpan(src.getSpan());
        if (src.getLocals() != null) {
            for (int i = 0; i < src.getLocals().size(); i++) {
                copySpan(src.getLocals().get(i), dst.getLocals().get(i));
            }
        }
        if (src.getStms() != null) {
            for (int i = 0; i < src.getStms().size(); i++) {
                copySpanStmt(src.getStms().get(i), dst.getStms().get(i));
            }
        }
    }

    private static void copySpan(Ast.Declare.T src, Ast.Declare.T dst) {
        if (src.getSpan() != null && dst.getSpan() == null) {
            dst.setSpan(src.getSpan());
        }
    }

    private static void copySpanStmt(Ast.Stmt.T src, Ast.Stmt.T dst) {
        if (src.getSpan() != null && dst.getSpan() == null) {
            dst.setSpan(src.getSpan());
        }
        if (src instanceof Ast.Stmt.Block sb && dst instanceof Ast.Stmt.Block db) {
            for (int i = 0; i < sb.getStmts().size(); i++) {
                copySpanStmt(sb.getStmts().get(i), db.getStmts().get(i));
            }
        } else if (src instanceof Ast.Stmt.If si && dst instanceof Ast.Stmt.If di) {
            copySpanStmt(si.getThenStmt(), di.getThenStmt());
            copySpanStmt(si.getElseStmt(), di.getElseStmt());
        } else if (src instanceof Ast.Stmt.While sw && dst instanceof Ast.Stmt.While dw) {
            copySpanStmt(sw.getBody(), dw.getBody());
        } else if (src instanceof Ast.Stmt.For sf && dst instanceof Ast.Stmt.For df) {
            copySpanStmt(sf.getInit(), df.getInit());
            copySpanStmt(sf.getBody(), df.getBody());
        } else if (src instanceof Ast.Stmt.Switch ss && dst instanceof Ast.Stmt.Switch ds) {
            if (ss.getClauses() != null && ds.getClauses() != null) {
                for (int i = 0; i < ss.getClauses().size(); i++) {
                    Ast.Stmt.CaseClause sc = ss.getClauses().get(i);
                    Ast.Stmt.CaseClause dc = ds.getClauses().get(i);
                    if (sc.getSpan() != null && dc.getSpan() == null) dc.setSpan(sc.getSpan());
                    copySpanExpr(sc.getLabel(), dc.getLabel());
                    if (sc.getBody() != null && dc.getBody() != null) {
                        for (int j = 0; j < sc.getBody().size(); j++) {
                            copySpanStmt(sc.getBody().get(j), dc.getBody().get(j));
                        }
                    }
                }
            }
        } else if (src instanceof Ast.Stmt.Return sr && dst instanceof Ast.Stmt.Return dr) {
            copySpanExpr(sr.getExpr(), dr.getExpr());
        } else if (src instanceof Ast.Stmt.ExprStmt se && dst instanceof Ast.Stmt.ExprStmt de) {
            copySpanExpr(se.getExpr(), de.getExpr());
        } else if (src instanceof Ast.Stmt.Assign sa && dst instanceof Ast.Stmt.Assign da) {
            copySpanExpr(sa.getExpr(), da.getExpr());
            copySpanExprId(sa.getId(), da.getId());
        } else if (src instanceof Ast.Stmt.FieldAssign sf && dst instanceof Ast.Stmt.FieldAssign df) {
            copySpanExpr(sf.getExpr(), df.getExpr());
        } else if (src instanceof Ast.Stmt.ArrayAssign sa && dst instanceof Ast.Stmt.ArrayAssign da) {
            copySpanExpr(sa.getIndex(), da.getIndex());
            copySpanExpr(sa.getExpr(), da.getExpr());
        } else if (src instanceof Ast.Stmt.DerefAssign sd && dst instanceof Ast.Stmt.DerefAssign dd) {
            copySpanExpr(sd.getExpr(), dd.getExpr());
        } else if (src instanceof Ast.Stmt.Printf sp && dst instanceof Ast.Stmt.Printf dp) {
            if (sp.getExprs() != null && dp.getExprs() != null) {
                for (int i = 0; i < Math.min(sp.getExprs().size(), dp.getExprs().size()); i++) {
                    copySpanExpr(sp.getExprs().get(i), dp.getExprs().get(i));
                }
            }
        }
    }

    private static void copySpanExpr(Ast.Expr.T src, Ast.Expr.T dst) {
        if (src == null || dst == null) return;
        if (src.getSpan() != null && dst.getSpan() == null) {
            dst.setSpan(src.getSpan());
        }
        if (src instanceof Ast.Expr.Add a && dst instanceof Ast.Expr.Add d) {
            copySpanExpr(a.getLeft(), d.getLeft());
            copySpanExpr(a.getRight(), d.getRight());
        } else if (src instanceof Ast.Expr.Sub s && dst instanceof Ast.Expr.Sub d) {
            copySpanExpr(s.getLeft(), d.getLeft());
            copySpanExpr(s.getRight(), d.getRight());
        } else if (src instanceof Ast.Expr.Mul m && dst instanceof Ast.Expr.Mul d) {
            copySpanExpr(m.getLeft(), d.getLeft());
            copySpanExpr(m.getRight(), d.getRight());
        } else if (src instanceof Ast.Expr.Div dsrc && dst instanceof Ast.Expr.Div ddst) {
            copySpanExpr(dsrc.getLeft(), ddst.getLeft());
            copySpanExpr(dsrc.getRight(), ddst.getRight());
        } else if (src instanceof Ast.Expr.Mod mo && dst instanceof Ast.Expr.Mod d) {
            copySpanExpr(mo.getLeft(), d.getLeft());
            copySpanExpr(mo.getRight(), d.getRight());
        } else if (src instanceof Ast.Expr.And a && dst instanceof Ast.Expr.And d) {
            copySpanExpr(a.getLeft(), d.getLeft());
            copySpanExpr(a.getRight(), d.getRight());
        } else if (src instanceof Ast.Expr.Or o && dst instanceof Ast.Expr.Or d) {
            copySpanExpr(o.getLeft(), d.getLeft());
            copySpanExpr(o.getRight(), d.getRight());
        } else if (src instanceof Ast.Expr.Not n && dst instanceof Ast.Expr.Not d) {
            copySpanExpr(n.getExpr(), d.getExpr());
        } else if (src instanceof Ast.Expr.GT g && dst instanceof Ast.Expr.GT d) {
            copySpanExpr(g.getLeft(), d.getLeft());
            copySpanExpr(g.getRight(), d.getRight());
        } else if (src instanceof Ast.Expr.LT l && dst instanceof Ast.Expr.LT d) {
            copySpanExpr(l.getLeft(), d.getLeft());
            copySpanExpr(l.getRight(), d.getRight());
        } else if (src instanceof Ast.Expr.GTE g && dst instanceof Ast.Expr.GTE d) {
            copySpanExpr(g.getLeft(), d.getLeft());
            copySpanExpr(g.getRight(), d.getRight());
        } else if (src instanceof Ast.Expr.LTE l && dst instanceof Ast.Expr.LTE d) {
            copySpanExpr(l.getLeft(), d.getLeft());
            copySpanExpr(l.getRight(), d.getRight());
        } else if (src instanceof Ast.Expr.EQ e && dst instanceof Ast.Expr.EQ d) {
            copySpanExpr(e.getLeft(), d.getLeft());
            copySpanExpr(e.getRight(), d.getRight());
        } else if (src instanceof Ast.Expr.NEQ e && dst instanceof Ast.Expr.NEQ d) {
            copySpanExpr(e.getLeft(), d.getLeft());
            copySpanExpr(e.getRight(), d.getRight());
        } else if (src instanceof Ast.Expr.Call c && dst instanceof Ast.Expr.Call d) {
            if (c.getInputParams() != null && d.getInputParams() != null) {
                for (int i = 0; i < Math.min(c.getInputParams().size(), d.getInputParams().size()); i++) {
                    copySpanExpr(c.getInputParams().get(i), d.getInputParams().get(i));
                }
            }
        } else if (src instanceof Ast.Expr.Field f && dst instanceof Ast.Expr.Field d) {
            copySpanExpr(f.getReceiver(), d.getReceiver());
        } else if (src instanceof Ast.Expr.ArrayAccess a && dst instanceof Ast.Expr.ArrayAccess d) {
            copySpanExpr(a.getIndex(), d.getIndex());
        } else if (src instanceof Ast.Expr.AddressOf ao && dst instanceof Ast.Expr.AddressOf dd) {
            copySpanExpr(ao.getOperand(), dd.getOperand());
        } else if (src instanceof Ast.Expr.Deref dr && dst instanceof Ast.Expr.Deref dd) {
            copySpanExpr(dr.getOperand(), dd.getOperand());
        } else if (src instanceof Ast.Expr.InitializerList il && dst instanceof Ast.Expr.InitializerList dl) {
            if (il.getElements() != null && dl.getElements() != null) {
                for (int i = 0; i < Math.min(il.getElements().size(), dl.getElements().size()); i++) {
                    copySpanExpr(il.getElements().get(i), dl.getElements().get(i));
                }
            }
        } else if (src instanceof Ast.Expr.PreInc pi && dst instanceof Ast.Expr.PreInc di) {
            copySpanExpr(pi.getExp(), di.getExp());
        } else if (src instanceof Ast.Expr.PostInc po && dst instanceof Ast.Expr.PostInc do2) {
            copySpanExpr(po.getExp(), do2.getExp());
        } else if (src instanceof Ast.Expr.PreDec pd && dst instanceof Ast.Expr.PreDec dd) {
            copySpanExpr(pd.getExp(), dd.getExp());
        } else if (src instanceof Ast.Expr.PostDec pdc && dst instanceof Ast.Expr.PostDec ddc) {
            copySpanExpr(pdc.getExp(), ddc.getExp());
        } else if (src instanceof Ast.Expr.UnaryPlus u && dst instanceof Ast.Expr.UnaryPlus d) {
            copySpanExpr(u.getExp(), d.getExp());
        } else if (src instanceof Ast.Expr.UnaryMinus u && dst instanceof Ast.Expr.UnaryMinus d) {
            copySpanExpr(u.getExp(), d.getExp());
        } else if (src instanceof Ast.Expr.BitNot b && dst instanceof Ast.Expr.BitNot d) {
            copySpanExpr(b.getExp(), d.getExp());
        } else if (src instanceof Ast.Expr.Ternary t && dst instanceof Ast.Expr.Ternary d) {
            copySpanExpr(t.getCondition(), d.getCondition());
            copySpanExpr(t.getTrueExpr(), d.getTrueExpr());
            copySpanExpr(t.getFalseExpr(), d.getFalseExpr());
        }
    }

    private static void copySpanExprId(Ast.Expr.Id src, Ast.Expr.Id dst) {
        if (src != null && dst != null && src.getSpan() != null && dst.getSpan() == null) {
            dst.setSpan(src.getSpan());
        }
    }

    private static ArrayList<Ast.Declare.T> copyDeclareList(ArrayList<Ast.Declare.T> list) {
        ArrayList<Ast.Declare.T> result = new ArrayList<>(list.size());
        for (Ast.Declare.T d : list) {
            if (d instanceof Ast.Declare.DeclareSingle s) {
                Ast.Declare.DeclareSingle nc = new Ast.Declare.DeclareSingle(
                        deepCopyType(s.getType()), s.getId(), deepCopyExpr(s.getInitExp()), s.getLineNum());
                nc.setVisibility(s.getVisibility());
                result.add(nc);
            }
        }
        return result;
    }

    private static ArrayList<Ast.Stmt.T> copyStmtList(ArrayList<Ast.Stmt.T> stmts) {
        ArrayList<Ast.Stmt.T> result = new ArrayList<>(stmts.size());
        for (Ast.Stmt.T s : stmts) {
            result.add((Ast.Stmt.T) deepCopyStmt(s));
        }
        return result;
    }

    private static Ast.Stmt.T deepCopyStmt(Ast.Stmt.T src) {
        final Map<Ast.Stmt.T, Ast.Stmt.T> cache = new ConcurrentHashMap<>();
        return (Ast.Stmt.T) copyStmtRecursive(src, cache);
    }

    private static Ast.Stmt.T copyStmtRecursive(Ast.Stmt.T src, Map<Ast.Stmt.T, Ast.Stmt.T> cache) {
        if (src == null) return null;
        Ast.Stmt.T cached = cache.get(src);
        if (cached != null) return cached;

        if (src instanceof Ast.Stmt.Block block) {
            ArrayList<Ast.Stmt.T> copied = copyStmtList(block.getStmts());
            Ast.Stmt.Block nb = new Ast.Stmt.Block(copied, block.getLineNum());
            cache.put(src, nb);
            return nb;
        } else if (src instanceof Ast.Stmt.If iff) {
            Ast.Stmt.If ni = new Ast.Stmt.If(
                    deepCopyExpr(iff.getCondition()),
                    copyStmtRecursive(iff.getThenStmt(), cache),
                    copyStmtRecursive(iff.getElseStmt(), cache),
                    iff.getLineNum());
            cache.put(src, ni);
            return ni;
        } else if (src instanceof Ast.Stmt.While w) {
            Ast.Stmt.While nw = new Ast.Stmt.While(
                    deepCopyExpr(w.getCondition()),
                    copyStmtRecursive(w.getBody(), cache),
                    w.getLineNum());
            cache.put(src, nw);
            return nw;
        } else if (src instanceof Ast.Stmt.For f) {
            Ast.Stmt.For nf = new Ast.Stmt.For(
                    copyStmtRecursive(f.getInit(), cache),
                    deepCopyExpr(f.getCondition()),
                    copyStmtRecursive(f.getUpdate(), cache),
                    copyStmtRecursive(f.getBody(), cache),
                    f.getLineNum());
            cache.put(src, nf);
            return nf;
        } else if (src instanceof Ast.Stmt.Return ret) {
            Ast.Stmt.Return nr = new Ast.Stmt.Return(
                    deepCopyExpr(ret.getExpr()), ret.getLineNum());
            cache.put(src, nr);
            return nr;
        } else if (src instanceof Ast.Stmt.Assign as) {
            Ast.Expr.Id nid = new Ast.Expr.Id(as.getId().getId(), as.getLineNum());
            Ast.Stmt.Assign na = new Ast.Stmt.Assign(nid, deepCopyExpr(as.getExpr()), as.getLineNum());
            cache.put(src, na);
            return na;
        } else if (src instanceof Ast.Stmt.VarDecl vd) {
            Ast.Declare.T nd = copyDeclareTop(vd.getDeclaration());
            Ast.Stmt.VarDecl nv = new Ast.Stmt.VarDecl(nd, vd.getLineNum());
            cache.put(src, nv);
            return nv;
        } else if (src instanceof Ast.Stmt.FieldAssign fa) {
            Ast.Stmt.FieldAssign nfa = new Ast.Stmt.FieldAssign(
                    deepCopyFieldExpr(fa.getTarget()), deepCopyExpr(fa.getExpr()), fa.getLineNum());
            cache.put(src, nfa);
            return nfa;
        } else if (src instanceof Ast.Stmt.ArrayAssign aa) {
            Ast.Stmt.ArrayAssign naa = new Ast.Stmt.ArrayAssign(
                    aa.getArrayName(), deepCopyExpr(aa.getIndex()), deepCopyExpr(aa.getExpr()), aa.getLineNum());
            naa.setFieldTarget(deepCopyFieldExpr(aa.getFieldTarget()));
            naa.setElementType(aa.getElementType());
            naa.setOp(aa.getOp());
            cache.put(src, naa);
            return naa;
        } else if (src instanceof Ast.Stmt.DerefAssign da) {
            Ast.Stmt.DerefAssign nda = new Ast.Stmt.DerefAssign(
                    deepCopyDerefExpr(da.getTarget()), deepCopyExpr(da.getExpr()), da.getLineNum());
            nda.setOp(da.getOp());
            cache.put(src, nda);
            return nda;
        } else if (src instanceof Ast.Stmt.Break b) {
            Ast.Stmt.Break nb = new Ast.Stmt.Break(b.getLineNum());
            cache.put(src, nb);
            return nb;
        } else if (src instanceof Ast.Stmt.Continue c) {
            Ast.Stmt.Continue nc = new Ast.Stmt.Continue(c.getLineNum());
            cache.put(src, nc);
            return nc;
        } else if (src instanceof Ast.Stmt.PrintLine pl) {
            Ast.Stmt.PrintLine npl = new Ast.Stmt.PrintLine();
            npl.setLineNum(pl.getLineNum());
            cache.put(src, npl);
            return npl;
        } else if (src instanceof Ast.Stmt.ExprStmt es) {
            Ast.Stmt.ExprStmt nes = new Ast.Stmt.ExprStmt(deepCopyExpr(es.getExpr()), es.getLineNum());
            cache.put(src, nes);
            return nes;
        } else if (src instanceof Ast.Stmt.Printf pf) {
            ArrayList<Ast.Expr.T> copiedExprs = new ArrayList<>();
            if (pf.getExprs() != null) {
                for (Ast.Expr.T e : pf.getExprs()) copiedExprs.add(deepCopyExpr(e));
            }
            Ast.Stmt.Printf npf = new Ast.Stmt.Printf(pf.getFormat(), copiedExprs, pf.getLineNum());
            cache.put(src, npf);
            return npf;
        } else if (src instanceof Ast.Stmt.Switch sw) {
            ArrayList<Ast.Stmt.CaseClause> copiedClauses = new ArrayList<>();
            if (sw.getClauses() != null) {
                for (Ast.Stmt.CaseClause cl : sw.getClauses()) {
                    ArrayList<Ast.Stmt.T> copiedBody = cl.getBody() != null
                            ? copyStmtList(cl.getBody()) : null;
                    Ast.Stmt.CaseClause ncl = new Ast.Stmt.CaseClause(
                            deepCopyExpr(cl.getLabel()), copiedBody, cl.getLineNum());
                    copiedClauses.add(ncl);
                }
            }
            Ast.Stmt.Switch nsw = new Ast.Stmt.Switch(deepCopyExpr(sw.getSubject()), copiedClauses, sw.getLineNum());
            cache.put(src, nsw);
            return nsw;
        }
        // Fallback: no-op clone (should not happen for valid methods)
        cache.put(src, src);
        return src;
    }

    private static Ast.Declare.T copyDeclareTop(Ast.Declare.T src) {
        if (src instanceof Ast.Declare.DeclareSingle s) {
            Ast.Declare.DeclareSingle ns = new Ast.Declare.DeclareSingle(
                    deepCopyType(s.getType()), s.getId(), deepCopyExpr(s.getInitExp()), s.getLineNum());
            ns.setVisibility(s.getVisibility());
            return ns;
        }
        return src;
    }

    private static Ast.Expr.T deepCopyExpr(Ast.Expr.T src) {
        if (src == null) return null;

        if (src instanceof Ast.Expr.Number n) {
            return new Ast.Expr.Number(deepCopyType(n.getType()), n.getValue(), n.getLineNum());
        } else if (src instanceof Ast.Expr.Id i) {
            return new Ast.Expr.Id(i.getId(), i.getType(), i.getLineNum());
        } else if (src instanceof Ast.Expr.Str s) {
            return new Ast.Expr.Str(s.getValue(), s.getLineNum());
        } else if (src instanceof Ast.Expr.True t) {
            return new Ast.Expr.True(t.getLineNum());
        } else if (src instanceof Ast.Expr.False f) {
            return new Ast.Expr.False(f.getLineNum());
        } else if (src instanceof Ast.Expr.Null n) {
            return new Ast.Expr.Null(n.getLineNum());
        } else if (src instanceof Ast.Expr.Add a) {
            return new Ast.Expr.Add(deepCopyExpr(a.getLeft()), deepCopyExpr(a.getRight()), a.getLineNum());
        } else if (src instanceof Ast.Expr.Sub s) {
            return new Ast.Expr.Sub(deepCopyExpr(s.getLeft()), deepCopyExpr(s.getRight()), s.getLineNum());
        } else if (src instanceof Ast.Expr.Mul m) {
            return new Ast.Expr.Mul(deepCopyExpr(m.getLeft()), deepCopyExpr(m.getRight()), m.getLineNum());
        } else if (src instanceof Ast.Expr.Div d) {
            return new Ast.Expr.Div(deepCopyExpr(d.getLeft()), deepCopyExpr(d.getRight()), d.getLineNum());
        } else if (src instanceof Ast.Expr.Mod mod) {
            return new Ast.Expr.Mod(deepCopyExpr(mod.getLeft()), deepCopyExpr(mod.getRight()), mod.getLineNum());
        } else if (src instanceof Ast.Expr.And a) {
            return new Ast.Expr.And(deepCopyExpr(a.getLeft()), deepCopyExpr(a.getRight()), a.getLineNum());
        } else if (src instanceof Ast.Expr.Or o) {
            return new Ast.Expr.Or(deepCopyExpr(o.getLeft()), deepCopyExpr(o.getRight()), o.getLineNum());
        } else if (src instanceof Ast.Expr.Not n) {
            return new Ast.Expr.Not(deepCopyExpr(n.getExpr()));
        } else if (src instanceof Ast.Expr.GT g) {
            return new Ast.Expr.GT(deepCopyExpr(g.getLeft()), deepCopyExpr(g.getRight()), g.getLineNum());
        } else if (src instanceof Ast.Expr.LT l) {
            return new Ast.Expr.LT(deepCopyExpr(l.getLeft()), deepCopyExpr(l.getRight()), l.getLineNum());
        } else if (src instanceof Ast.Expr.GTE g) {
            return new Ast.Expr.GTE(deepCopyExpr(g.getLeft()), deepCopyExpr(g.getRight()), g.getLineNum());
        } else if (src instanceof Ast.Expr.LTE l) {
            return new Ast.Expr.LTE(deepCopyExpr(l.getLeft()), deepCopyExpr(l.getRight()), l.getLineNum());
        } else if (src instanceof Ast.Expr.EQ e) {
            return new Ast.Expr.EQ(deepCopyExpr(e.getLeft()), deepCopyExpr(e.getRight()), e.getLineNum());
        } else if (src instanceof Ast.Expr.NEQ e) {
            return new Ast.Expr.NEQ(deepCopyExpr(e.getLeft()), deepCopyExpr(e.getRight()), e.getLineNum());
        } else if (src instanceof Ast.Expr.Call c) {
            ArrayList<Ast.Expr.T> args = c.getInputParams() != null
                    ? copyExprList(c.getInputParams()) : new ArrayList<>();
            Ast.Expr.Call nc = new Ast.Expr.Call(c.getName(), args, c.getLineNum(), c.getReturnType());
            return nc;
        } else if (src instanceof Ast.Expr.Field f) {
            return deepCopyFieldExpr(f);
        } else if (src instanceof Ast.Expr.ArrayAccess a) {
            Ast.Expr.ArrayAccess na = new Ast.Expr.ArrayAccess(
                    a.getArrayName(), deepCopyExpr(a.getIndex()), a.getLineNum());
            na.setFieldTarget(deepCopyFieldExpr(a.getFieldTarget()));
            na.setElementType(a.getElementType());
            return na;
        } else if (src instanceof Ast.Expr.ArrayLength a) {
            return new Ast.Expr.ArrayLength(a.getArrayName(), a.getLineNum());
        } else if (src instanceof Ast.Expr.AddressOf a) {
            return new Ast.Expr.AddressOf(deepCopyExpr(a.getOperand()), a.getLineNum());
        } else if (src instanceof Ast.Expr.Deref d) {
            return new Ast.Expr.Deref(deepCopyExpr(d.getOperand()), d.getLineNum());
        } else if (src instanceof Ast.Expr.InitializerList il) {
            ArrayList<Ast.Expr.T> elems = il.getElements() != null
                    ? copyExprList(il.getElements()) : new ArrayList<>();
            Ast.Expr.InitializerList nil = new Ast.Expr.InitializerList(elems, il.getLineNum());
            nil.setType(il.getType());
            return nil;
        } else if (src instanceof Ast.Expr.PreInc p) {
            return new Ast.Expr.PreInc(deepCopyExpr(p.getExp()), p.getLineNum());
        } else if (src instanceof Ast.Expr.PostInc p) {
            return new Ast.Expr.PostInc(deepCopyExpr(p.getExp()), p.getLineNum());
        } else if (src instanceof Ast.Expr.PreDec p) {
            return new Ast.Expr.PreDec(deepCopyExpr(p.getExp()), p.getLineNum());
        } else if (src instanceof Ast.Expr.PostDec p) {
            return new Ast.Expr.PostDec(deepCopyExpr(p.getExp()), p.getLineNum());
        } else if (src instanceof Ast.Expr.UnaryPlus u) {
            return new Ast.Expr.UnaryPlus(deepCopyExpr(u.getExp()), u.getLineNum());
        } else if (src instanceof Ast.Expr.UnaryMinus u) {
            return new Ast.Expr.UnaryMinus(deepCopyExpr(u.getExp()), u.getLineNum());
        } else if (src instanceof Ast.Expr.BitNot b) {
            return new Ast.Expr.BitNot(deepCopyExpr(b.getExp()), b.getLineNum());
        } else if (src instanceof Ast.Expr.Ternary t) {
            return new Ast.Expr.Ternary(
                    deepCopyExpr(t.getCondition()),
                    deepCopyExpr(t.getTrueExpr()),
                    deepCopyExpr(t.getFalseExpr()),
                    t.getLineNum());
        }
        return src;
    }

    private static ArrayList<Ast.Expr.T> copyExprList(ArrayList<Ast.Expr.T> list) {
        ArrayList<Ast.Expr.T> result = new ArrayList<>(list.size());
        for (Ast.Expr.T e : list) result.add(deepCopyExpr(e));
        return result;
    }

    /**
     * Deep-copies a Field expression (for struct field access chains).
     * Returns a new Field with deep-copied receiver.
     */
    private static Ast.Expr.Field deepCopyFieldExpr(Ast.Expr.Field src) {
        if (src == null) return null;
        ArrayList<String> newPath = src.getPath() != null ? new ArrayList<>(src.getPath()) : new ArrayList<>();
        Ast.Expr.Field nf = new Ast.Expr.Field(
                deepCopyExpr(src.getReceiver()), newPath, src.isPointerBase(), src.getLineNum());
        nf.setType(src.getType());
        return nf;
    }

    private static Ast.Expr.Deref deepCopyDerefExpr(Ast.Expr.Deref src) {
        if (src == null) return null;
        return new Ast.Expr.Deref(deepCopyExpr(src.getOperand()), src.getLineNum());
    }

    private static Ast.Type.T deepCopyType(Ast.Type.T src) {
        if (src == null) return null;
        if (src instanceof Ast.Type.IntArray a) return new Ast.Type.IntArray(a.getSize());
        if (src instanceof Ast.Type.ByteArray b) return new Ast.Type.ByteArray(b.getSize());
        if (src instanceof Ast.Type.ShortArray s) return new Ast.Type.ShortArray(s.getSize());
        if (src instanceof Ast.Type.CharArray c) return new Ast.Type.CharArray(c.getSize());
        if (src instanceof Ast.Type.LongArray l) return new Ast.Type.LongArray(l.getSize());
        if (src instanceof Ast.Type.FloatArray f) return new Ast.Type.FloatArray(f.getSize());
        if (src instanceof Ast.Type.DoubleArray d) return new Ast.Type.DoubleArray(d.getSize());
        if (src instanceof Ast.Type.BoolArray bo) return new Ast.Type.BoolArray(bo.getSize());
        if (src instanceof Ast.Type.StringArray str) return new Ast.Type.StringArray(str.getSize());
        if (src instanceof Ast.Type.Pointer p) return new Ast.Type.Pointer(deepCopyType(p.getPointee()));
        // Value types are stateless — safe to reuse the singleton
        return src;
    }
}