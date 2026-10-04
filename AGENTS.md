# LemonC — Agent Guide

Verified against source on 2026-10-04 (`src/main/java/site/ilemon`, Java 21, Maven, JUnit 4.13.2, artifact `LemonC-0.1-beta`).

## What LemonC is

Single-pass-pipeline compiler for the Lemon language (C-like syntax) with two backends:

```
Lexer → Parser → Semantic → AstOptimizer → AstToIrLowerer (LemonIR)
                                     ↘ ARC (OwnershipAnalyzer on IR)
                                     ↘ NullFlowAnalyzer
                                           → { C backend, JVM backend }
```

Entry point: `site.ilemon.compiler.LemonC` (main class in assembly jar).

## Source/test/example layout

| Directory | Contents |
|---|---|
| `src/main/java/site/ilemon/` | Compiler source — packages: `lexer`, `parser`, `ast`, `semantic`, `optimizer`, `ir`, `arc`, `flow`, `backend/c`, `backend/jvm`, `compiler`, `diagnostic`, `type`, `visitor`, `util` |
| `src/test/java/` | JUnit 4 tests (no package) — ~60 test classes |
| `examples/*.lemon` | Positive examples (≈80 files); exercised by `AllExamplesJvmTest` |
| `examples/*.c` | Expected C output for selected examples |
| `examples/errors/` | Negative examples expected to produce diagnostics |
| `examples/full_feature_matrix/` | Multi-file benchmark; `expected.tsv` |
| `examples/large_benchmark/` | 6-module world-simulation benchmark |
| `examples/modules/`, `modules_structs/`, `modules_pointers/` | Import/struct/pointer cross-module tests |
| `examples/pointer/`, `pointer_full/`, `pointer_showcase/` | Pointer tests, incl. invalid subdirs |
| `runtime/` | C runtime (`lemon_runtime.c`) + headers; source in `runtime/src/`, `runtime/include/` |
| `tools/debug/` | Debug utilities (class file parser, stack tracer) |

## Pipeline and key classes

| Stage | Package | Key class(es) |
|---|---|---|
| Lexer | `lexer` | `Lexer.java` (static `KEYWORDS` map), `TokenKind.java`, `Token.java` |
| Parser | `parser` | `Parser.java` — recursive descent, `parseStmt()` dispatch, `isStatementStart()`, recovery via `synchronizeToStatementBoundary()` |
| AST | `ast` | `Ast.java` — `Stmt.T` (abstract, mutable POJO with `lineNum`+`span`), `Expr.T`, `Type.T` (sealed), `Declare.T`, `StructDecl`, `EnumDecl`, `ConstDecl` |
| Semantic | `semantic` | `SemanticVisitor.java`, `MethodVarTable.java` — single visitor; `loopDepth`, `currMethodLocalVar`, `mTable.enterScope()/exitScope()` |
| Optimizer | `optimizer` | `AstOptimizer.java` — const-folding, dead branch elimination. Unknown `Stmt` kinds fall through unchanged. |
| IR lowerer | `ir` | `AstToIrLowerer.java`, `IrInstruction.java`, `IrModule.java`, `IrFunction.java`, `BasicBlock.java` |
| ARC | `arc` | `OwnershipAnalyzer.java`, `RefcountSimulator.java`, `OwnershipIr.java`, `MemoryOp.java` |
| Null flow | `flow` | `NullFlowAnalyzer.java`, `NullFlowResult.java`, `Nullability.java` |
| C backend | `backend/c` | `CBackend.java`, `CFunctionEmitter.java`, `CInstructionEmitter.java`, `CTypeEmitter.java` |
| JVM backend | `backend/jvm` | `JvmBackend.java`, `JvmMethodEmitter.java`, `JvmInstructionEmitter.java`, `JvmCodeBuilder.java` |
| Diagnostics | `diagnostic` | `DiagnosticCodes.java` (E0001–E9001), `DiagnosticEngine.java`, `DiagnosticRenderer.java` |
| Modules | `compiler` | `ModuleLoader.java`, `MethodCallRewriter.java` |
| Visitor interface | `visitor` | `ISemanticVisitor.java` — **only 2 implementors**: `SemanticVisitor`, `MethodCallRewriter` |
| Type rules | `type` | `TypeRules.java` |
| Entry point | `compiler` | `LemonC.java` |

## Supported language features

- Scalar types: `int`, `long`, `short`, `byte`, `char`, `float`, `double`, `bool`
- Arrays: fixed-size and dynamic (`int[]`, `string[]`, etc.), `.length` property, initializers
- Structs: `struct Name { type field; ... }`, value semantics, field access (`.`, `->`), struct arrays
- Enums: `enum Name { A, B = 2, ... }`, re-exported via module imports as `alias_NAME`
- Switch/case/default: fallthrough semantics, nested break support
- Pointers: `*`, `&`, `->` (auto-deref), null literal, pointer arithmetic (add/sub), multi-level pointers
- ARC: ownership analyzer emits `RETAIN`/`RELEASE` during IR lowering; no separate ARC IR pass
- Null safety: flow-sensitive nullability analysis for pointer derefs/field loads
- Constants: `const int MAX = 10;` at global scope only (literal initializer)
- Modules: `import Foo;` merging structs/enums/constants, method call rewriting
- Control flow: `if/else`, `while`, `for`, `break`, `continue`

## C/JVM backend rules

- **Shared IR only.** No backend-specific opcodes. Backends receive the same `IrModule`.
- **C backend:** hoists all instruction results as C locals; emits `goto <label>` for `BRANCH`/`COND_BRANCH`; labels as `<name>:;`. Must compile clean under `-Wall -Wextra -Werror` (label/unused suppression in `CFunctionEmitter`).
- **JVM backend:** `JvmCodeBuilder` is the **only** place that knows binary bytecode encoding (`label(name)`, `branch(opcode, target)`). Symbolic labels patched in `toBytecode()`. ARC calls dropped (GC). Enum values inlined as ints.
- `PHI` nodes must be resolved before reaching either backend.
- C/JVM parity: end-to-end tests (`MultiBackendTest`, `PointerMultiBackendTest`) compile the same `.lemon` through both and compare output.

## ARC / null-safety / control-flow rules

- ARC (`OwnershipAnalyzer`) walks IR statements; ownership blocks are per CFG region. `Break`/`While`/`For` handled in `emitStatement`.
- Null flow (`NullFlowAnalyzer`) uses fixpoint loops for `While`/`For`. Results used by both backends to elide redundant null checks.
- `break`/`continue` require `loopDepth > 0 || switchDepth > 0` in semantic (`visit(Ast.Stmt.Break)`). IR: innermost breakable wins — if a loop was pushed inside a switch (`loopStack.peek().breakTarget == switchStack.peek()`), loop target is used; otherwise switch target.
- Switch IR layout is **order-sensitive**: `MethodLoweringContext.emit()` skips instructions after a terminator, so dispatch-chain linking relies on **physical block order** (`[entry+subject, dispatches..., default, bodies..., exit]`), spliced via `ctx.blocks` list manipulation in `lowerSwitch`.
- Return-path analysis (`flowOfStatement` in `NullFlowAnalyzer`) models switch fallthrough: a clause without a trailing terminator inherits the next clause's termination (computed back-to-front). A clause ending in `break`/`continue` never returns. A switch returns on all paths only when every clause terminates AND a `default` exists.
- `case` labels: `resolveSwitchCaseLabel` resolves int literals/negative forms (`integralLiteralValue`), enum members (`Color.RED` via `resolveEnum`, bare `ADD_OP` via `enumMemberTable`), and integer named constants. Enum labels rejected on integer subjects and vice versa.

## Build and test commands

```
mvn test                  # full JUnit 4 suite
mvn -Dtest=SwitchTest test         # switch suite
mvn -Dtest=EnumTest test           # enum suite
mvn -Dtest=ReturnPathAnalysisTest test  # return-path analysis
mvn -Dtest=LargeBenchmarkTest test # 6-module stress test
mvn -Dtest=FullFeatureMatrixTest test # feature matrix
mvn -Dtest=CBackendTest test       # C backend parity
mvn -Dtest=JvmBackendTest test     # JVM backend
mvn -Dtest=MultiBackendTest test   # C vs JVM parity
```

Test conventions: JUnit 4 classes in `src/test/java` (no package). Diagnostic assertions use `DiagnosticTestSupport`/`DiagnosticEngine`. Examples in `examples/*.lemon` exercised by `AllExamplesJvmTest`.

## Coding conventions

- AST nodes: mutable POJOs, `accept()` delegates to `ISemanticVisitor`; always set `lineNum` and `setSpan(...)` when parser creates a node.
- Adding an `Ast.Stmt.Xxx` node requires updating **every** `ISemanticVisitor` implementor (currently only `SemanticVisitor` and `MethodCallRewriter`). Search `visit(Ast.Stmt.` to find them.
- IR lowering: no backend-specific IR. Emit only shared opcodes; `PHI` must never reach backends.
- Diagnostics: prefer `typeError`/`semanticError` with stable codes from `DiagnosticCodes` over plain `error(...)`. Include `span` where available.
- Local-variable scope in semantic: mirror `visit(Ast.Stmt.For)` — `mTable.enterScope()` before body, snapshot/restore `currMethodLocalVar`, `exitScope()` after.
- New diagnostic codes: add a constant to `DiagnosticCodes.java`, keep the `E[0-9]{4}` pattern.

## Preferred agent workflow

1. Read this `AGENTS.md`.
2. Read the relevant section of `guide.md` (architecture, feature-specific, or testing).
3. Search for relevant symbols/files (`search_files`, `grep`).
4. Inspect only the affected code (parser branch, AST class, visitor, IR lowerer, etc.).
5. Implement the change.
6. Run targeted tests (`mvn -Dtest=<Class> test`).
7. Run `mvn test` to verify no regressions.
8. Update documentation only when behavior changes.

## Points of caution

- `AstOptimizer.optimizeStmt` falls through to `return stmt` for unknown `Stmt` kinds — verify coverage by reading, not trusting.
- `ModuleLoader.collectStatementImports` recurses into `Block`/`If`/`While`/`For`/`Switch` (case bodies included). Extend if a new statement can contain `import`.
- C backend: blocks with no incoming branch get no label (dedup in `CFunctionEmitter`); unreachable trailing blocks still emit.
- Enum member constants are re-exported on import as `alias_NAME` (`ModuleLoader`); enum type checks use `resolveEnum` with module alias support.
- Do not modify expected test outputs to hide failures; fix the compiler.
