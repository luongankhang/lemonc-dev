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
                    Ast.Method.MethodSingle exported = method;
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
}