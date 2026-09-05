package site.ilemon.compiler;

import site.ilemon.ast.Ast;
import site.ilemon.backend.BackendOptions;
import site.ilemon.backend.BackendResult;
import site.ilemon.backend.c.CBackend;
import site.ilemon.backend.jvm.JvmBackend;
import site.ilemon.exception.CompilerException;
import site.ilemon.exception.ParseException;
import site.ilemon.diagnostic.Diagnostic;
import site.ilemon.diagnostic.DiagnosticCodes;
import site.ilemon.diagnostic.DiagnosticEngine;
import site.ilemon.diagnostic.DiagnosticRenderer;
import site.ilemon.ir.ArcOptimizer;
import site.ilemon.ir.AstToIrLowerer;
import site.ilemon.ir.IrModule;
import site.ilemon.lexer.Lexer;
import site.ilemon.lexer.Token;
import site.ilemon.optimizer.AstOptimizer;
import site.ilemon.parser.Parser;
import site.ilemon.semantic.SemanticVisitor;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/**
 * LemonC command line entry point.
 *
 * <p>Multi-backend pipeline:</p>
 * <pre>
 *   Lemon Source → Lexer → Parser → Semantic → Optimizer → ARC → LemonIR
 *     ├── JvmBackend → .class   (direct JVM bytecode, no Jasmin)
 *     └── CBackend   → .c → native compiler
 * </pre>
 */
public class LemonC {

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            CompilerOptions options = CompilerOptions.parse(args, err);
            if (options == null) {
                usage(err);
                return 1;
            }
            if (!options.sourcePath.endsWith(".lemon")) {
                err.println("error: source file must end with .lemon, got: " + options.sourcePath);
                usage(err);
                return 1;
            }

            File sourceFile = new File(options.sourcePath);
            if (!sourceFile.exists()) {
                err.println("error: file does not exist - " + options.sourcePath);
                return 1;
            }
            if (!sourceFile.canRead()) {
                err.println("error: file is not readable - " + options.sourcePath);
                return 1;
            }

            Lexer lexer = new Lexer(sourceFile);
            Parser parser = new Parser(lexer);
            Ast.Program.T program;
            try {
                program = parser.parse();
            } catch (ParseException e) {
                for (Diagnostic diagnostic : parser.getDiagnostics()) {
                    err.println(new DiagnosticRenderer((file, line) -> lexer.getSourceLine(line)).render(diagnostic));
                }
                return 1;
            }
            if (options.dumpTokens) {
                dumpTokens(lexer, out);
            }

            new ModuleLoader().resolve(program, sourceFile.toPath());

            SemanticVisitor semantic = SemanticVisitor.collecting();
            semantic.visit(program);
            if (!semantic.passOrNot()) {
                err.println("compile failed: semantic analysis has errors");
                printSemanticDiagnostics(semantic, lexer, err);
                return 1;
            }
            if (options.dumpAst) {
                out.println("== AST ==");
                out.print(AstPrinter.print(program));
            }

            Ast.Program.T optimizedProgram = new AstOptimizer().optimize(program);

            if (options.dumpArc || options.arc || options.arcVerify || options.arcAnalysis || options.arcDebug) {
                site.ilemon.arc.OwnershipAnalyzer analyzer = new site.ilemon.arc.OwnershipAnalyzer();
                site.ilemon.arc.OwnershipIr arcIr = analyzer.analyze(optimizedProgram);

                if (options.dumpArc || options.arcAnalysis || options.arcDebug) {
                    out.println("== ARC ==");
                    if (options.arcDebug) {
                        for (site.ilemon.arc.OwnershipFunction func : arcIr.functions()) {
                            out.println("Function: " + func.name() + " (returnManaged=" + func.isReturnManaged() + ")");
                            out.println("  Managed locals: " + func.managedLocals().keySet());
                            out.println("  Managed params: " + func.managedParameters());
                            for (site.ilemon.arc.OwnershipBlock block : func.blocks()) {
                                out.println("  Block [" + block.name() + "]:");
                                for (site.ilemon.arc.MemoryOp op : block.operations()) {
                                    out.println("    " + op);
                                }
                                List<String> succNames = block.successors().stream().map(site.ilemon.arc.OwnershipBlock::name).toList();
                                out.println("    -> " + block.terminatorType() + " to " + succNames);
                            }
                        }
                    } else {
                        for (var operation : arcIr.operations()) {
                            out.println(operation);
                        }
                    }
                }

                if (options.arc || options.arcVerify || options.arcDebug) {
                    site.ilemon.arc.RefcountSimulator simulator = new site.ilemon.arc.RefcountSimulator();
                    try {
                        simulator.verify(arcIr);
                    } catch (IllegalStateException e) {
                        err.println("compile failed: ARC ownership verification failed");
                        for (Diagnostic diagnostic : simulator.getDiagnostics()) {
                            err.println(new DiagnosticRenderer((file, line) -> lexer.getSourceLine(line)).render(diagnostic));
                        }
                        return 1;
                    }
                }
            }

            // Shared backend-independent LemonIR — the single source of truth
            // for both the JVM and C backends.
            IrModule irModule = new AstToIrLowerer().lower(optimizedProgram);

            // ARC optimization pass: eliminate redundant retain/release pairs
            new ArcOptimizer().optimize(irModule);

            if (options.dumpIr) {
                out.println("== IR ==");
                out.print(IrPrinter.print(irModule));
            }

            BackendOptions backendOptions = new BackendOptions(
                    options.target,
                    sourceFile.toPath().toAbsolutePath().normalize(),
                    Path.of("target", "lemonc"),
                    options.outputPath == null ? null : Path.of(options.outputPath),
                    options.verbose,
                    options.arcDebug);

            if ("c".equalsIgnoreCase(options.target) || options.emitC) {
                BackendResult cResult = new CBackend().emit(irModule, backendOptions);
                if (options.verbose) {
                    out.println("Wrote C source to: " + cResult.primaryOutput());
                    if (cResult.outputs().size() > 1) {
                        out.println("Native compilation succeeded: " + cResult.outputs().get(1));
                    }
                }
                return 0;
            }

            BackendResult jvmResult = new JvmBackend().emit(irModule, backendOptions);
            Path classFile = jvmResult.primaryOutput();
            if (options.verbose) {
                out.println("Generated: " + classFile);
            }
            if (!classFile.toFile().isFile() || classFile.toFile().length() == 0) {
                throw new CompilerException("JVM backend did not generate class file: " + classFile);
            }
            return 0;
        } catch (CompilerException e) {
            err.println("compile failed:");
            err.println(new DiagnosticRenderer((file, line) -> null).render(e.getDiagnostic()));
            return 1;
        } catch (IOException e) {
            err.println("io error: " + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            var engine = new DiagnosticEngine();
            Diagnostic diagnostic = engine.error(DiagnosticCodes.INTERNAL_COMPILER_ERROR)
                    .message("internal compiler error: " + (e.getMessage() == null ? "unknown runtime failure" : e.getMessage()))
                    .primary(null, "unexpected compiler failure")
                    .report();
            err.println("compile failed:");
            err.println(new DiagnosticRenderer((file, line) -> null).render(diagnostic));
            return 1;
        }
    }

    private static void printSemanticDiagnostics(SemanticVisitor semantic, Lexer lexer, PrintStream err) {
        for (Diagnostic diagnostic : semantic.getDiagnostics()) {
            err.println(new DiagnosticRenderer((file, line) -> lexer.getSourceLine(line)).render(diagnostic));
        }
    }

    private static void dumpTokens(Lexer lexer, PrintStream out) {
        out.println("== TOKENS ==");
        for (Token token : lexer.tokens) {
            out.printf("%4d:%-3d %-14s  %s%n",
                    token.lineNumber, token.columnNumber, token.kind, token.lexeme);
        }
    }

    private static void usage(PrintStream err) {
        err.println("usage: java -jar LemonC.jar <source.lemon> [--target <jvm|c>] [--emit-c] [-o <output>] [--dump-tokens] [--dump-ast] [--dump-ir] [--dump-arc] [--arc] [--arc-verify] [--arc-analysis] [--arc-debug] [--verbose]");
    }

    private static final class CompilerOptions {
        private final String sourcePath;
        private boolean dumpTokens;
        private boolean dumpAst;
        private boolean dumpIr;
        private boolean dumpArc;
        private boolean arc;
        private boolean arcVerify;
        private boolean arcAnalysis;
        private boolean arcDebug;
        private boolean verbose;
        private String target = "jvm";
        private boolean emitC;
        private String outputPath;

        private CompilerOptions(String sourcePath) {
            this.sourcePath = sourcePath;
        }

        private static CompilerOptions parse(String[] args, PrintStream err) {
            if (args == null || args.length < 1) {
                return null;
            }
            CompilerOptions options = new CompilerOptions(args[0]);
            for (int i = 1; i < args.length; i++) {
                if ("--dump-tokens".equals(args[i])) {
                    options.dumpTokens = true;
                } else if ("--dump-ast".equals(args[i])) {
                    options.dumpAst = true;
                } else if ("--dump-ir".equals(args[i])) {
                    options.dumpIr = true;
                } else if ("--dump-arc".equals(args[i])) {
                    options.dumpArc = true;
                    options.arcAnalysis = true;
                } else if ("--arc".equals(args[i])) {
                    options.arc = true;
                } else if ("--arc-verify".equals(args[i])) {
                    options.arcVerify = true;
                } else if ("--arc-analysis".equals(args[i])) {
                    options.arcAnalysis = true;
                } else if ("--arc-debug".equals(args[i])) {
                    options.arcDebug = true;
                } else if ("--verbose".equals(args[i])) {
                    options.verbose = true;
                } else if ("--target".equals(args[i])) {
                    if (i + 1 >= args.length) {
                        err.println("error: --target requires an argument (jvm or c)");
                        return null;
                    }
                    String t = args[++i];
                    if (!"jvm".equalsIgnoreCase(t) && !"c".equalsIgnoreCase(t)) {
                        err.println("error: unknown target - " + t + " (supported: jvm, c)");
                        return null;
                    }
                    options.target = t.toLowerCase();
                } else if ("--emit-c".equals(args[i])) {
                    options.emitC = true;
                } else if ("-o".equals(args[i]) || "--output".equals(args[i])) {
                    if (i + 1 >= args.length) {
                        err.println("error: " + args[i] + " requires an argument");
                        return null;
                    }
                    options.outputPath = args[++i];
                } else {
                    err.println("error: unknown option - " + args[i]);
                    return null;
                }
            }
            return options;
        }
    }
}