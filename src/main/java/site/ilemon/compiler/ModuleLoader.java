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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Loads Lemon modules as AST units; imported source is never textually included. */
public final class ModuleLoader {
    private final Map<Path, Ast.MainClass.MainClassSingle> cache = new HashMap<>();
    private final Set<Path> loading = new HashSet<>();

    public void resolve(Ast.Program.T program, Path sourcePath) throws IOException {
        Ast.MainClass.MainClassSingle main = (Ast.MainClass.MainClassSingle) ((Ast.Program.ProgramSingle) program).getMainClass();
        loadImports(main, sourcePath.toAbsolutePath().normalize());
    }

    private void loadImports(Ast.MainClass.MainClassSingle owner, Path ownerPath) throws IOException {
        Set<String> aliases = new HashSet<>();
        for (Ast.ImportDecl importDecl : owner.getImports()) {
            if (!aliases.add(importDecl.getName())) {
                throw moduleError("duplicate module import '" + importDecl.getName() + "'", importDecl.getSpan());
            }
            Path importedPath = ownerPath.getParent().resolve(importDecl.getPath()).normalize().toAbsolutePath();
            if (!Files.isRegularFile(importedPath)) {
                throw moduleError("module not found: " + importDecl.getPath(), importDecl.getSpan());
            }
            Ast.MainClass.MainClassSingle imported = load(importedPath);
            for (Ast.Method.T methodNode : imported.getMethods()) {
                Ast.Method.MethodSingle method = (Ast.Method.MethodSingle) methodNode;
                if (method.getVisibility() == Ast.Visibility.PUBLIC && !"main".equals(method.getId())) {
                    method.setId(importDecl.getName() + "_" + method.getId());
                    owner.getMethods().add(method);
                }
            }
        }
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