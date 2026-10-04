# LemonC — Development Roadmap

> **Last verified**: 2026-10-04 against `src/main/java/site/ilemon`, Java 21, Maven, JUnit 4.13.2, artifact `LemonC-0.1-beta`.
> **Test baseline**: `mvn test` → **615 tests pass** (0 failures, 0 errors, 0 skipped) across **66 test classes**; **~175 Lemon examples** exercised by `AllExamplesJvmTest`.

---

## 1. Current Status

### 1.1 Architecture

```
Lexer → Parser → Semantic → AstOptimizer
                           ↘ ARC (OwnershipAnalyzer)   ─┐
                           ↘ NullFlowAnalyzer           │
                           ↘ AstToIrLowerer (LemonIR)  │
                                                     ↓ │
                                           { C backend, JVM backend }
```

Entry point: [`site.ilemon.compiler.LemonC`](src/main/java/site/ilemon/compiler/LemonC.java).

### 1.2 Pipeline (actual)

| Stage | Package | Key class |
|---|---|---|
| Lexer | `lexer` | [`Lexer.java`](src/main/java/site/ilemon/lexer/Lexer.java), [`TokenKind.java`](src/main/java/site/ilemon/lexer/TokenKind.java), [`Token.java`](src/main/java/site/ilemon/lexer/Token.java) |
| Parser | `parser` | [`Parser.java`](src/main/java/site/ilemon/parser/Parser.java) — LL(2) recursive descent, `isVarDeclarationStart()`, recovery via `synchronizeToStatementBoundary()` |
| Semantic | `semantic` | [`SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java), [`MethodVarTable.java`](src/main/java/site/ilemon/semantic/MethodVarTable.java), [`ScopeManager.java`](src/main/java/site/ilemon/semantic/ScopeManager.java), [`Symbol.java`](src/main/java/site/ilemon/semantic/Symbol.java), [`ImportSymbol.java`](src/main/java/site/ilemon/semantic/ImportSymbol.java) |
| Optimizer | `optimizer` | [`AstOptimizer.java`](src/main/java/site/ilemon/optimizer/AstOptimizer.java) — const-folding, dead branch elimination |
| ARC | `arc` | [`OwnershipAnalyzer.java`](src/main/java/site/ilemon/arc/OwnershipAnalyzer.java), [`RefcountSimulator.java`](src/main/java/site/ilemon/arc/RefcountSimulator.java), [`OwnershipIr.java`](src/main/java/site/ilemon/arc/OwnershipIr.java), [`MemoryOp.java`](src/main/java/site/ilemon/arc/MemoryOp.java) |
| Null flow | `flow` | [`NullFlowAnalyzer.java`](src/main/java/site/ilemon/flow/NullFlowAnalyzer.java), [`NullFlowResult.java`](src/main/java/site/ilemon/flow/NullFlowResult.java), [`Nullability.java`](src/main/java/site/ilemon/flow/Nullability.java) |
| IR | `ir` | [`AstToIrLowerer.java`](src/main/java/site/ilemon/ir/AstToIrLowerer.java), [`IrInstruction.java`](src/main/java/site/ilemon/ir/IrInstruction.java), [`IrModule.java`](src/main/java/site/ilemon/ir/IrModule.java), [`IrFunction.java`](src/main/java/site/ilemon/ir/IrFunction.java), [`BasicBlock.java`](src/main/java/site/ilemon/ir/BasicBlock.java), [`IrType.java`](src/main/java/site/ilemon/ir/IrType.java), [`IrValue.java`](src/main/java/site/ilemon/ir/IrValue.java), [`IrVerifier.java`](src/main/java/site/ilemon/ir/IrVerifier.java) |
| IR optimization | `ir` | [`ArcOptimizer.java`](src/main/java/site/ilemon/ir/ArcOptimizer.java) — eliminates redundant retain/release pairs and dead stores |
| C backend | `backend/c` | [`CBackend.java`](src/main/java/site/ilemon/backend/c/CBackend.java), [`CFunctionEmitter.java`](src/main/java/site/ilemon/backend/c/CFunctionEmitter.java), [`CInstructionEmitter.java`](src/main/java/site/ilemon/backend/c/CInstructionEmitter.java), [`CTypeEmitter.java`](src/main/java/site/ilemon/backend/c/CTypeEmitter.java), [`CModuleEmitter.java`](src/main/java/site/ilemon/backend/c/CModuleEmitter.java), [`ConstantPropagation.java`](src/main/java/site/ilemon/backend/c/ConstantPropagation.java), [`DeadStoreElimination.java`](src/main/java/site/ilemon/backend/c/DeadStoreElimination.java), [`NativeToolchain.java`](src/main/java/site/ilemon/backend/c/NativeToolchain.java) |
| JVM backend | `backend/jvm` | [`JvmBackend.java`](src/main/java/site/ilemon/backend/jvm/JvmBackend.java), [`JvmMethodEmitter.java`](src/main/java/site/ilemon/backend/jvm/JvmMethodEmitter.java), [`JvmInstructionEmitter.java`](src/main/java/site/ilemon/backend/jvm/JvmInstructionEmitter.java), [`JvmCodeBuilder.java`](src/main/java/site/ilemon/backend/jvm/JvmCodeBuilder.java), [`JvmLocalAllocator.java`](src/main/java/site/ilemon/backend/jvm/JvmLocalAllocator.java), [`JvmStackTracker.java`](src/main/java/site/ilemon/backend/jvm/JvmStackTracker.java), [`JvmTypeMapper.java`](src/main/java/site/ilemon/backend/jvm/JvmTypeMapper.java), [`JvmClassWriter.java`](src/main/java/site/ilemon/backend/jvm/JvmClassWriter.java), [`JvmMethod.java`](src/main/java/site/ilemon/backend/jvm/JvmMethod.java) |
| Diagnostics | `diagnostic` | [`DiagnosticCodes.java`](src/main/java/site/ilemon/diagnostic/DiagnosticCodes.java) (E0001–E9001), [`DiagnosticEngine.java`](src/main/java/site/ilemon/diagnostic/DiagnosticEngine.java), [`DiagnosticRenderer.java`](src/main/java/site/ilemon/diagnostic/DiagnosticRenderer.java), [`DiagnosticJsonExporter.java`](src/main/java/site/ilemon/diagnostic/DiagnosticJsonExporter.java), [`Severity.java`](src/main/java/site/ilemon/diagnostic/Severity.java) |
| Modules | `compiler` | [`ModuleLoader.java`](src/main/java/site/ilemon/compiler/ModuleLoader.java), [`MethodCallRewriter.java`](src/main/java/site/ilemon/compiler/MethodCallRewriter.java), [`LemonC.java`](src/main/java/site/ilemon/compiler/LemonC.java), [`AstPrinter.java`](src/main/java/site/ilemon/compiler/AstPrinter.java), [`IrPrinter.java`](src/main/java/site/ilemon/compiler/IrPrinter.java) |
| Visitor interface | `visitor` | [`ISemanticVisitor.java`](src/main/java/site/ilemon/visitor/ISemanticVisitor.java) — **only 2 implementors**: `SemanticVisitor`, `MethodCallRewriter` |
| Type rules | `type` | [`TypeRules.java`](src/main/java/site/ilemon/type/TypeRules.java) |
| Backend contract | `backend` | [`Backend.java`](src/main/java/site/ilemon/backend/Backend.java), [`BackendOptions.java`](src/main/java/site/ilemon/backend/BackendOptions.java), [`BackendResult.java`](src/main/java/site/ilemon/backend/BackendResult.java) |

### 1.3 Backend status

| Backend | Status | Notes |
|---|---|---|
| JVM | ✅ Production | Writes `.class` files directly; no Jasmin/external assembler dependency. |
| C | ✅ Production | Emits C99 source; compiles via gcc/clang. Runs `-Wall -Wextra -Werror` clean (labels/unused suppressed). |
| Parity | ✅ Verified | `MultiBackendTest`, `PointerMultiBackendTest`, `ModuleStructScopeTest`, `NullSafetyFlowTest` confirm identical outputs. |

### 1.4 Language feature status

| Feature | Status |
|---|---|
| Scalar types: `byte`, `short`, `char`, `int`, `long`, `float`, `double`, `bool` | ✅ |
| `string` / `String` (JVM `java.lang.String`) | ✅ |
| `void` return type | ✅ |
| Arrays: fixed-size, all primitive types + `string[]` | ✅ |
| Array `.length`, indexing, initialization (`{...}`) | ✅ |
| Structs with value semantics, field access (`.` / `->`) | ✅ |
| Struct visibility (`pub` / private), module scoping | ✅ |
| Enums with nominal typing, explicit values, cross-module access | ✅ |
| Enums in structs, pointers to enums, switch labels | ✅ |
| Switch/case/default with fallthrough, nested break | ✅ |
| Pointers: `*`, `&`, `->`, `null`, multi-level (`int**`) | ✅ |
| Pointer safety: `E2008` (escape), `E3014` (arithmetic), `E3015` (write through pointer) | ✅ |
| ARC: `RETAIN`/`RELEASE` during lowering; `--arc` verification CLI | ✅ |
| Null flow analysis: flow-sensitive nullability for derefs/field loads | ✅ |
| Constants: `const int MAX = 10;` global scope only | ✅ |
| Modules: `import alias = @import("file.lemon");` with re-export (`alias_NAME`) | ✅ |
| Control flow: `if/else`, `while`, `for`, `break`, `continue` | ✅ |
| Operators: `+ - * / % ~ & | ^ !` (unary), `+= -= *= /= %=`, prefix/postfix `++ --`, ternary `? :`, short-circuit `&& ||` | ✅ |
| Numeric widening: `byte/short/char → int → long → float → double` | ✅ |
| `printf` / `printLine`; `%d` for integer-like, `%f` for floats | ✅ |
| Comments: `//` and `/* ... */` | ✅ |
| Identifier underscores | ✅ |

### 1.5 Test/build status

```
mvn test                  # 615 tests, 0 failures, 0 errors, 0 skipped
mvn package               # builds fat jar: LemonC-0.1-beta-jar-with-dependencies.jar
```

Test classes (66): see [Section 7](#7-testing-roadmap).

### 1.6 Major known limitations

| Limitation | Rationale / Status |
|---|---|
| No class instantiation (`new ClassName()`) | Object model deferred; structs are C-style value records. |
| No multi-dimensional arrays (`int[][]`) | Statically sized 1D arrays only. |
| No pointer arithmetic (`p + 1`, `p++`) | Forbidden by design; E3014 diagnostic. |
| No string scalar variables (`string s = ...`) | `string` supported in literals, `printf`, `string[]` only. |
| No struct comparison / struct arrays as elements | Structural limitation of value-semantics design. |
| No `%s` in `printf` | Format specifiers limited to `%d` and `%f`. |
| No object-oriented features (inheritance, virtual methods) | Out of scope for current language. |
| ARC does not break cycles | No reverse references currently; future `weak` references planned. |
| Only 2 `ISemanticVisitor` implementors | Adding `Ast.Stmt.Xxx` requires updating both — search `visit(Ast.Stmt.` to find them. |
| `AstOptimizer.optimizeStmt` falls through for unknown `Stmt` kinds | Verify coverage by reading, not trusting. |
| `ModuleLoader.collectStatementImports` only recurses into `Block`/`If`/`While`/`For`/`Switch` | Extend if a new statement can contain `import`. |

---

## 2. Completed

### 2.1 Core Pipeline

| Feature | Status | Key source areas | Relevant tests/examples | Implementation notes |
|---|---|---|---|---|
| Lexer (DFA tokenization) | ✅ | [`lexer/Lexer.java`](src/main/java/site/ilemon/lexer/Lexer.java), [`TokenKind.java`](src/main/java/site/ilemon/lexer/TokenKind.java), [`LexerState.java`](src/main/java/site/ilemon/lexer/LexerState.java) | [`LexerTest.java`](src/test/java/LexerTest.java) | Static `KEYWORDS` map; 63 keywords. |
| Parser (LL(2) recursive descent) | ✅ | [`parser/Parser.java`](src/main/java/site/ilemon/parser/Parser.java) | [`ParserTest.java`](src/test/java/ParserTest.java), [`ParserRecoveryTest.java`](src/test/java/ParserRecoveryTest.java) | `isVarDeclarationStart()` for LL(2) disambiguation; `synchronizeToStatementBoundary()` for error recovery. |
| AST node definitions | ✅ | [`ast/Ast.java`](src/main/java/site/ilemon/ast/Ast.java) | All tests | Mutable POJOs with `lineNum` + `span`; `accept()` delegates to `ISemanticVisitor`. |
| Semantic visitor (type checking, scoping) | ✅ | [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java), [`MethodVarTable.java`](src/main/java/site/ilemon/semantic/MethodVarTable.java), [`ScopeManager.java`](src/main/java/site/ilemon/semantic/ScopeManager.java) | [`SemanticTest.java`](src/test/java/SemanticTest.java), [`LocalVarDeclTest.java`](src/test/java/LocalVarDeclTest.java) | `loopDepth`, `currMethodLocalVar`, `mTable.enterScope()/exitScope()`. |
| Visitor interface | ✅ | [`visitor/ISemanticVisitor.java`](src/main/java/site/ilemon/visitor/ISemanticVisitor.java) | All tests | Default methods for newer visitors (PreInc, PostInc, PreDec, PostDec, UnaryPlus, UnaryMinus, BitNot, Ternary, ExprStmt, Enum). |
| Type rules | ✅ | [`type/TypeRules.java`](src/main/java/site/ilemon/type/TypeRules.java) | [`SemanticTest.java`](src/test/java/SemanticTest.java), [`NewPrimitiveTypesCompilerTest.java`](src/test/java/NewPrimitiveTypesCompilerTest.java) | Widening, compatibility checks. |

### 2.2 Language Features

| Feature | Status | Key source areas | Relevant tests/examples | Implementation notes |
|---|---|---|---|---|
| Scalar types (byte/short/char/int/long/float/double/bool/string/void) | ✅ | [`ast/Ast.java`](src/main/java/site/ilemon/ast/Ast.java) (Type.T), [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java) | [`ByteCompilerTest.java`](src/test/java/ByteCompilerTest.java), [`LongCompilerTest.java`](src/test/java/LongCompilerTest.java), [`ShortCompilerTest.java`](src/test/java/ShortCompilerTest.java), [`CharCompilerTest.java`](src/test/java/CharCompilerTest.java), [`FloatTest01.lemon`](examples/FloatTest01.lemon), [`DoubleTest01.lemon`](examples/DoubleTest01.lemon) | JVM descriptors: `B`, `S`, `C`, `I`, `J`, `F`, `D`, `Z`, `Ljava/lang/String;`. |
| Arrays (all primitive types + string[]) | ✅ | [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java), [`ir/AstToIrLowerer.java`](src/main/java/site/ilemon/ir/AstToIrLowerer.java) | [`ArrayLengthTest.java`](src/test/java/ArrayLengthTest.java), [`ByteArrayCompilerTest.java`](src/test/java/ByteArrayCompilerTest.java), [`CharArrayCompilerTest.java`](src/test/java/CharArrayCompilerTest.java), [`LongArrayCompilerTest.java`](src/test/java/LongArrayCompilerTest.java), [`ShortArrayCompilerTest.java`](src/test/java/ShortArrayCompilerTest.java), [`StringArrayCompilerTest.java`](src/test/java/StringArrayCompilerTest.java) | `.length` property; `BOUNDS_CHECK` opcode. |
| Structs (value semantics, field access) | ✅ | [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java), [`ir/AstToIrLowerer.java`](src/main/java/site/ilemon/ir/AstToIrLowerer.java) | [`StructTest.java`](src/test/java/StructTest.java), [`StructArcTest.java`](src/test/java/StructArcTest.java), [`DumpStructCopy.java`](src/test/java/DumpStructCopy.java), [`DumpStructNames.java`](src/test/java/DumpStructNames.java) | `STRUCT_COPY`, `FIELD_LOAD`, `FIELD_STORE` opcodes. |
| Struct visibility & module scoping | ✅ | [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java), [`compiler/ModuleLoader.java`](src/main/java/site/ilemon/compiler/ModuleLoader.java) | [`StructVisibilityTest.java`](src/test/java/StructVisibilityTest.java), [`ModuleStructScopeTest.java`](src/test/java/ModuleStructScopeTest.java), [`examples/module_struct_scope/`](examples/module_struct_scope/) | `E2005 SEM_INVALID_SCOPE` for private struct access. |
| Enums (nominal, explicit values) | ✅ | [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java), [`ir/AstToIrLowerer.java`](src/main/java/site/ilemon/ir/AstToIrLowerer.java) | [`EnumTest.java`](src/test/java/EnumTest.java), [`examples/enum_showcase.lemon`](examples/enum_showcase.lemon) | Re-exported as `alias_NAME`; backing type `int`. |
| Switch/case/default (fallthrough) | ✅ | [`parser/Parser.java`](src/main/java/site/ilemon/parser/Parser.java), [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java), [`ir/AstToIrLowerer.java`](src/main/java/site/ilemon/ir/AstToIrLowerer.java) | [`SwitchTest.java`](src/test/java/SwitchTest.java), [`ReturnPathAnalysisTest.java`](src/test/java/ReturnPathAnalysisTest.java), [`examples/switch_showcase.lemon`](examples/switch_showcase.lemon) | Physical block order in `ctx.blocks`; dispatch-chain linking. |
| Pointers (address-of, dereference, null) | ✅ | [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java), [`ir/AstToIrLowerer.java`](src/main/java/site/ilemon/ir/AstToIrLowerer.java), [`flow/NullFlowAnalyzer.java`](src/main/java/site/ilemon/flow/NullFlowAnalyzer.java) | [`PointerTest.java`](src/test/java/PointerTest.java), [`DumpPointerTest.java`](src/test/java/DumpPointerTest.java), [`examples/pointer/`](examples/pointer/) | `E3010`–`E3015` for pointer type safety. |
| Multi-level pointers | ✅ | [`ir/AstToIrLowerer.java`](src/main/java/site/ilemon/ir/AstToIrLowerer.java) | [`examples/pointer/pointer_multi_level.lemon`](examples/pointer/pointer_multi_level.lemon) | `int**` support. |
| Pointer safety (escape, arithmetic, write) | ✅ | [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java) | [`examples/pointer/invalid/`](examples/pointer/invalid/) | `E2008`, `E3014`, `E3015`. |
| Pointer full feature set | ✅ | [`ir/AstToIrLowerer.java`](src/main/java/site/ilemon/ir/AstToIrLowerer.java), [`backend/c/CInstructionEmitter.java`](src/main/java/site/ilemon/backend/c/CInstructionEmitter.java), [`backend/jvm/JvmInstructionEmitter.java`](src/main/java/site/ilemon/backend/jvm/JvmInstructionEmitter.java) | [`examples/pointer_full/`](examples/pointer_full/), [`PointerMultiBackendTest.java`](src/test/java/PointerMultiBackendTest.java) | 12 test cases, parity verified. |
| Pointer showcase | ✅ | Same as above | [`examples/pointer_showcase/pointer_showcase.lemon`](examples/pointer_showcase/pointer_showcase.lemon), [`PointerShowcaseTest.java`](src/test/java/PointerShowcaseTest.java) | End-to-end C/JVM parity. |
| ARC (ownership analysis) | ✅ | [`arc/OwnershipAnalyzer.java`](src/main/java/site/ilemon/arc/OwnershipAnalyzer.java), [`arc/RefcountSimulator.java`](src/main/java/site/ilemon/arc/RefcountSimulator.java) | [`ArcOwnershipTest.java`](src/test/java/ArcOwnershipTest.java), [`ArcControlFlowTest.java`](src/test/java/ArcControlFlowTest.java), [`ArcDiagnosticTest.java`](src/test/java/ArcDiagnosticTest.java), [`ArcCliTest.java`](src/test/java/ArcCliTest.java), [`ImportScopeArcTest.java`](src/test/java/ImportScopeArcTest.java), [`JvmBackendArcDebugTest.java`](src/test/java/JvmBackendArcDebugTest.java), [`examples/arc/`](examples/arc/) | CLI: `--arc`, `--arc-verify`, `--arc-analysis`, `--arc-debug`, `--dump-arc`. |
| Null flow analysis | ✅ | [`flow/NullFlowAnalyzer.java`](src/main/java/site/ilemon/flow/NullFlowAnalyzer.java) | [`NullSafetyFlowTest.java`](src/test/java/NullSafetyFlowTest.java), [`examples/null_safety.lemon`](examples/null_safety.lemon) | Fixpoint loops for loops; condition narrowing; early return pruning. |
| Constants (global `const`) | ✅ | [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java) | [`GlobalConstTest.java`](src/test/java/GlobalConstTest.java), [`examples/const_demo.lemon`](examples/) | `E2006` (immutability), `E2007` (initializer). |
| Modules (import, re-export) | ✅ | [`compiler/ModuleLoader.java`](src/main/java/site/ilemon/compiler/ModuleLoader.java), [`compiler/MethodCallRewriter.java`](src/main/java/site/ilemon/compiler/MethodCallRewriter.java) | [`ModuleSystemTest.java`](src/test/java/ModuleSystemTest.java), [`examples/modules/`](examples/modules/), [`examples/modules_structs/`](examples/modules_structs/) | `alias_NAME` re-exports; cycle detection. |
| Control flow (if/while/for/break/continue) | ✅ | [`parser/Parser.java`](src/main/java/site/ilemon/parser/Parser.java), [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java), [`ir/AstToIrLowerer.java`](src/main/java/site/ilemon/ir/AstToIrLowerer.java) | [`examples/NestedLoops.lemon`](examples/NestedLoops.lemon), [`examples/LoopTest.lemon`](examples/LoopTest.lemon) | Loop context stack; innermost breakable wins. |
| Operators (+, -, *, /, %, ~, &, |, ^, !, ++, --, +=, etc.) | ✅ | [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java), [`ir/AstToIrLowerer.java`](src/main/java/site/ilemon/ir/AstToIrLowerer.java) | [`OperatorTest.java`](src/test/java/OperatorTest.java), [`examples/operator/OperatorShowcase.lemon`](examples/operator/OperatorShowcase.lemon) | Single-evaluation of LHS for compound assignment. |
| Ternary operator | ✅ | [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java), [`ir/AstToIrLowerer.java`](src/main/java/site/ilemon/ir/AstToIrLowerer.java) | [`examples/`](examples/) | Lowers to branchy CFG with phi merging. |
| Short-circuit boolean logic | ✅ | [`ir/AstToIrLowerer.java`](src/main/java/site/ilemon/ir/AstToIrLowerer.java) | [`examples/MatrixShortCircuit.lemon`](examples/full_feature_matrix/MatrixShortCircuit.lemon) | Branchy control flow in shared IR. |
| I/O (printf, printLine) | ✅ | [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java), [`backend/c/CInstructionEmitter.java`](src/main/java/site/ilemon/backend/c/CInstructionEmitter.java), [`backend/jvm/JvmInstructionEmitter.java`](src/main/java/site/ilemon/backend/jvm/JvmInstructionEmitter.java) | [`examples/PrintfMixed.lemon`](examples/PrintfMixed.lemon), [`examples/PrintfLiteral.lemon`](examples/PrintfLiteral.lemon) | `%d` for int-like, `%f` for floats. |
| Comments (single-line, multi-line) | ✅ | [`lexer/Lexer.java`](src/main/java/site/ilemon/lexer/Lexer.java) | [`LexerTest.java`](src/test/java/LexerTest.java) | `/* ... */` and `//`. |

### 2.3 IR & Backends

| Feature | Status | Key source areas | Relevant tests/examples | Implementation notes |
|---|---|---|---|---|
| LemonIR (backend-neutral CFG) | ✅ | [`ir/AstToIrLowerer.java`](src/main/java/site/ilemon/ir/AstToIrLowerer.java), [`ir/IrInstruction.java`](src/main/java/site/ilemon/ir/IrInstruction.java), [`ir/IrModule.java`](src/main/java/site/ilemon/ir/IrModule.java) | [`LemonIrTest.java`](src/test/java/LemonIrTest.java), [`DumpIr.java`](src/test/java/DumpIr.java) | 21 opcodes; `PHI` must be resolved before backends. |
| IR verifier | ✅ | [`ir/IrVerifier.java`](src/main/java/site/ilemon/ir/IrVerifier.java) | [`LemonIrTest.java`](src/test/java/LemonIrTest.java) | Structural validation. |
| IR ARC optimizer | ✅ | [`ir/ArcOptimizer.java`](src/main/java/site/ilemon/ir/ArcOptimizer.java) | [`ArcOwnershipTest.java`](src/test/java/ArcOwnershipTest.java) | Eliminates redundant retain/release pairs. |
| C backend | ✅ | [`backend/c/CBackend.java`](src/main/java/site/ilemon/backend/c/CBackend.java), [`CFunctionEmitter.java`](src/main/java/site/ilemon/backend/c/CFunctionEmitter.java), [`CInstructionEmitter.java`](src/main/java/site/ilemon/backend/c/CInstructionEmitter.java), [`CTypeEmitter.java`](src/main/java/site/ilemon/backend/c/CTypeEmitter.java), [`CModuleEmitter.java`](src/main/java/site/ilemon/backend/c/CModuleEmitter.java) | [`CBackendTest.java`](src/test/java/CBackendTest.java), [`NativeEndToEndTest.java`](src/test/java/NativeEndToEndTest.java), [`NativeRuntimeSafetyTest.java`](src/test/java/NativeRuntimeSafetyTest.java) | `-Wall -Wextra -Werror` clean; hoists all IR results as C locals. |
| JVM backend | ✅ | [`backend/jvm/JvmBackend.java`](src/main/java/site/ilemon/backend/jvm/JvmBackend.java), [`JvmMethodEmitter.java`](src/main/java/site/ilemon/backend/jvm/JvmMethodEmitter.java), [`JvmInstructionEmitter.java`](src/main/java/site/ilemon/backend/jvm/JvmInstructionEmitter.java), [`JvmCodeBuilder.java`](src/main/java/site/ilemon/backend/jvm/JvmCodeBuilder.java) | [`JvmBackendTest.java`](src/test/java/JvmBackendTest.java), [`AllExamplesJvmTest.java`](src/test/java/AllExamplesJvmTest.java) | Direct bytecode emission; no Jasmin dependency. |
| C/JVM parity | ✅ | Both backends consume same `IrModule` | [`MultiBackendTest.java`](src/test/java/MultiBackendTest.java), [`PointerMultiBackendTest.java`](src/test/java/PointerMultiBackendTest.java) | End-to-end output comparison. |
| Constant propagation (C) | ✅ | [`backend/c/ConstantPropagation.java`](src/main/java/site/ilemon/backend/c/ConstantPropagation.java) | [`CBackendTest.java`](src/test/java/CBackendTest.java) | Pre-emission pass. |
| Dead store elimination (C) | ✅ | [`backend/c/DeadStoreElimination.java`](src/main/java/site/ilemon/backend/c/DeadStoreElimination.java) | [`CBackendTest.java`](src/test/java/CBackendTest.java) | Pre-emission pass. |
| Native toolchain discovery | ✅ | [`backend/c/NativeToolchain.java`](src/main/java/site/ilemon/backend/c/NativeToolchain.java) | [`NativeEndToEndTest.java`](src/test/java/NativeEndToEndTest.java) | gcc/clang auto-discovery. |

### 2.4 Diagnostics & Tooling

| Feature | Status | Key source areas | Relevant tests/examples | Implementation notes |
|---|---|---|---|---|
| Diagnostic codes (E0001–E9001) | ✅ | [`diagnostic/DiagnosticCodes.java`](src/main/java/site/ilemon/diagnostic/DiagnosticCodes.java) | [`DiagnosticTest.java`](src/test/java/DiagnosticTest.java), [`SemanticDiagnosticTest.java`](src/test/java/SemanticDiagnosticTest.java) | Stable identifiers grouped by phase. |
| Diagnostic engine | ✅ | [`diagnostic/DiagnosticEngine.java`](src/main/java/site/ilemon/diagnostic/DiagnosticEngine.java) | [`DiagnosticEngineTest.java`](src/test/java/DiagnosticEngineTest.java) | Collects diagnostics; fail-fast or collect mode. |
| Diagnostic renderer | ✅ | [`diagnostic/DiagnosticRenderer.java`](src/main/java/site/ilemon/diagnostic/DiagnosticRenderer.java) | [`DiagnosticRendererTest.java`](src/test/java/DiagnosticRendererTest.java) | Source snippets with `^~~~` underlining. |
| JSON exporter | ✅ | [`diagnostic/DiagnosticJsonExporter.java`](src/main/java/site/ilemon/diagnostic/DiagnosticJsonExporter.java) | [`DiagnosticJsonExporterTest.java`](src/test/java/DiagnosticJsonExporterTest.java) | Structured error export. |
| CLI flags | ✅ | [`compiler/LemonC.java`](src/main/java/site/ilemon/compiler/LemonC.java) | [`LemonCCliTest.java`](src/test/java/LemonCCliTest.java) | `--target`, `--dump-tokens`, `--dump-ast`, `--dump-ir`, `--arc`, `--arc-verify`, `--arc-analysis`, `--arc-debug`, `--dump-arc`. |
| AST printer | ✅ | [`compiler/AstPrinter.java`](src/main/java/site/ilemon/compiler/AstPrinter.java) | Debug use. | Human-readable AST dump. |
| IR printer | ✅ | [`compiler/IrPrinter.java`](src/main/java/site/ilemon/compiler/IrPrinter.java) | Debug use. | Human-readable IR dump. |

### 2.5 Optimization

| Feature | Status | Key source areas | Relevant tests/examples | Implementation notes |
|---|---|---|---|---|
| AST const-folding | ✅ | [`optimizer/AstOptimizer.java`](src/main/java/site/ilemon/optimizer/AstOptimizer.java) | [`AstOptimizerTest.java`](src/test/java/AstOptimizerTest.java), [`examples/OptimizationTest.lemon`](examples/OptimizationTest.lemon) | `AstOptimizer.optimizeStmt` falls through for unknown Stmt kinds. |
| AST dead branch elimination | ✅ | [`optimizer/AstOptimizer.java`](src/main/java/site/ilemon/optimizer/AstOptimizer.java) | Same as above | `if (true) { A } else { B }` → `A`. |
| Algebraic simplification | ✅ | [`optimizer/AstOptimizer.java`](src/main/java/site/ilemon/optimizer/AstOptimizer.java) | Same as above | `x * 1 → x`, `x + 0 → x`, etc. |
| Dead loop elimination | ✅ | [`optimizer/AstOptimizer.java`](src/main/java/site/ilemon/optimizer/AstOptimizer.java) | Same as above | `while (false) { ... }` removed. |

### 2.6 Testing & Verification

| Feature | Status | Key source areas | Relevant tests/examples | Implementation notes |
|---|---|---|---|---|
| Unit test suite (615 tests) | ✅ | [`src/test/java/`](src/test/java/) | `mvn test` | 66 test classes, JUnit 4. |
| Example regression suite | ✅ | [`examples/*.lemon`](examples/) | [`AllExamplesJvmTest.java`](src/test/java/AllExamplesJvmTest.java) | ~175 examples verified against manifest. |
| Full feature matrix | ✅ | [`examples/full_feature_matrix/`](examples/full_feature_matrix/) | [`FullFeatureMatrixTest.java`](src/test/java/FullFeatureMatrixTest.java) | 13 matrix tests + 8 invalid cases. |
| Large benchmark (6-module) | ✅ | [`examples/large_benchmark/`](examples/large_benchmark/) | [`LargeBenchmarkTest.java`](src/test/java/LargeBenchmarkTest.java) | World simulation with structs, enums, pointers. |

---

## 3. In Progress

_No actively in-progress features. All planned features from prior versions have been implemented._

---

## 4. Planned

### P0 — Correctness / Blockers

_No P0 items currently._

### P1 — Important Language / Compiler Features

| ID | Feature | Priority | Status | Depends on | Relevant files/classes | Relevant tests | Implementation notes | Acceptance criteria |
|---|---|---|---|---|---|---|---|---|
| LEA-001 | Local address escape in nested scopes | P1 | Planned | — | [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java) | `SemanticDiagnosticTest.java` | `E2008` currently checks top-level method locals; nested block escapes may need refinement. | All nested local escape paths rejected with `E2008`. |
| LEA-002 | String scalar variables | P1 | Planned | — | [`ast/Ast.java`](src/main/java/site/ilemon/ast/Ast.java), [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java), [`ir/AstToIrLowerer.java`](src/main/java/site/ilemon/ir/AstToIrLowerer.java) | New test class + examples | JVM: `Ljava/lang/String;`; C: `char*` with runtime management. | `string s = "hello";` compiles and runs on both backends. |
| LEA-003 | `%s` format specifier in printf | P1 | Planned | LEA-002 | [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java), [`backend/c/CInstructionEmitter.java`](src/main/java/site/ilemon/backend/c/CInstructionEmitter.java), [`backend/jvm/JvmInstructionEmitter.java`](src/main/java/site/ilemon/backend/jvm/JvmInstructionEmitter.java) | `SemanticDiagnosticTest.java` | Extend format string parsing and type checking. | `printf("%s\n", str)` works on both backends. |

### P2 — Optimization / Performance

| ID | Feature | Priority | Status | Depends on | Relevant files/classes | Relevant tests | Implementation notes | Acceptance criteria |
|---|---|---|---|---|---|---|---|---|
| OPT-001 | Constant propagation (JVM backend) | P2 | Planned | — | [`backend/jvm/JvmInstructionEmitter.java`](src/main/java/site/ilemon/backend/jvm/JvmInstructionEmitter.java) | New test class | Mirror C backend's `ConstantPropagation` pass. | Compiled bytecode has fewer instructions for constant expressions. |
| OPT-002 | Common subexpression elimination | P2 | Planned | OPT-001 | [`ir/AstToIrLowerer.java`](src/main/java/site/ilemon/ir/AstToIrLowerer.java) | New test class | Track repeated identical IR expressions within a basic block. | Reduced IR instruction count for repeated expressions. |
| OPT-003 | Loop invariant code motion | P2 | Planned | OPT-002 | [`ir/AstToIrLowerer.java`](src/main/java/site/ilemon/ir/AstToIrLowerer.java) | Benchmark regression | Hoist invariant expressions out of loop bodies. | Benchmark throughput improvement measurable. |
| OPT-004 | Tail call optimization | P2 | Planned | — | [`ir/AstToIrLowerer.java`](src/main/java/site/ilemon/ir/AstToIrLowerer.java) | [`examples/RecursiveMergeSort.lemon`](examples/RecursiveMergeSort.lemon) | Detect tail-position calls and emit goto instead of call. | Deep recursion examples don't overflow stack. |

### P3 — Tooling / Documentation

| ID | Feature | Priority | Status | Depends on | Relevant files/classes | Relevant tests | Implementation notes | Acceptance criteria |
|---|---|---|---|---|---|---|---|---|
| DOC-001 | English-language tutorial | P3 | Planned | — | `docs/tutorial/` (new) | — | "Build a JVM compiler from scratch with LemonC." | Tutorial covers full pipeline with hand-hold. |
| DOC-002 | Visual dump snapshots | P3 | Planned | — | `docs/assets/` | — | Screenshots of `--dump-tokens`, `--dump-ast`, `--dump-ir` output. | Images in README/docs. |
| CI-001 | GitHub Actions badge | P3 | Planned | — | `.github/workflows/` (new) | — | Add workflow to run `mvn test` on push/PR. | Badge shows green in README. |
| REL-001 | v0.2.0 release | P3 | Planned | — | `pom.xml` | — | Bump version; publish jar. | `LemonC-0.2.0-jar-with-dependencies.jar` on releases page. |

---

## 5. Known Bugs / Limitations

| # | Issue | Severity | Status | Relevant files | Workaround |
|---|---|---|---|---|---|
| 1 | `AstOptimizer.optimizeStmt` falls through for unknown `Stmt` kinds — unoptimized but silent | Low | Accepted | [`optimizer/AstOptimizer.java`](src/main/java/site/ilemon/optimizer/AstOptimizer.java) | Verify coverage by reading the chain. |
| 2 | `ModuleLoader.collectStatementImports` only recurses into `Block`/`If`/`While`/`For`/`Switch` | Low | Accepted | [`compiler/ModuleLoader.java`](src/main/java/site/ilemon/compiler/ModuleLoader.java) | Extend if a new statement can contain `import`. |
| 3 | C backend: blocks with no incoming branch get no label (dedup in `CFunctionEmitter`); unreachable trailing blocks still emit | Low | Accepted | [`backend/c/CFunctionEmitter.java`](src/main/java/site/ilemon/backend/c/CFunctionEmitter.java) | Known behavior; no user-visible impact. |
| 4 | Pointer arithmetic (`p + 1`, `p++` for pointer types) is forbidden with `E3014` | By design | Accepted | [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java) | Language boundary — no workaround needed. |
| 5 | Multi-dimensional arrays (`int[][]`) not supported | By design | Accepted | [`ast/Ast.java`](src/main/java/site/ilemon/ast/Ast.java) | Use flat 1D arrays with manual indexing. |
| 6 | Whole array assignment (`a = b`) disallowed | By design | Accepted | [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java) | Element-by-element copy required. |
| 7 | `printf` only supports `%d` and `%f` | By design | Accepted | [`semantic/SemanticVisitor.java`](src/main/java/site/ilemon/semantic/SemanticVisitor.java) | Format specifiers limited by design. |
| 8 | ARC does not break reference cycles | By design | Accepted | [`arc/OwnershipAnalyzer.java`](src/main/java/site/ilemon/arc/OwnershipAnalyzer.java) | No reverse references in current language; future `weak` refs planned. |
| 9 | Fail-fast: compilation stops at first error | Medium | Accepted | [`diagnostic/DiagnosticEngine.java`](src/main/java/site/ilemon/diagnostic/DiagnosticEngine.java) | Run compiler multiple times to collect all errors. |
| 10 | No performance benchmarking against gcc/javac | Low | Accepted | — | — | Benchmarks added as P2 if pursued. |

---

## 6. Architecture Roadmap

Track planned improvements to core compiler subsystems.

### 6.1 Parser

| Item | Priority | Status | Notes |
|---|---|---|---|
| Improve error recovery granularity | P2 | Planned | Current `synchronizeToStatementBoundary()` is coarse. |
| Support `do/while` loops | P3 | Planned | Can be desugared to `while` — low value. |

### 6.2 Semantic / Type System

| Item | Priority | Status | Notes |
|---|---|---|---|
| Cross-module private field access via pointer | P1 | Planned | `->` access should check field visibility even through pointers. |
| Generic type parameters | P3 | Planned | Out of scope for current language design. |
| Implicit `int → float`/`double` promotion in `printf` args | P2 | Planned | Currently requires explicit cast or widening at assignment. |

### 6.3 AST

| Item | Priority | Status | Notes |
|---|---|---|---|
| Add `Stmt.ExprStmt` default visitor handling | P3 | Done | Default method exists in `ISemanticVisitor`; implementation sparse. |
| Consider immutable AST nodes | P3 | Planned | Current mutable POJOs simplify lowering; immutable would require redesign. |

### 6.4 ARC / Null Flow

| Item | Priority | Status | Notes |
|---|---|---|---|
| `weak` reference category | P2 | Planned | Needed when objects with reverse references are added. |
| Cycle detection diagnostics | P2 | Planned | Report cycles at compile time rather than leaking at runtime. |
| Extend null flow to array element nullability | P2 | Planned | Track null elements in `string[]` and managed element arrays. |
| Integrate ARC diagnostics into main compilation (not just `--arc`) | P1 | Planned | `ArcDiagnosticTest` shows diagnostics work; make them default. |

### 6.5 LemonIR

| Item | Priority | Status | Notes |
|---|---|---|---|
| Add `PHI` resolution pass (pre-backend) | P1 | Planned | `PHI` must never reach backends; implement elimination pass. |
| SSA form for locals | P2 | Planned | Replace temp variables with SSA; enables more optimizations. |
| Add `BRANCH_WEIGHT` opcode for profile-guided optimization | P3 | Planned | Future: use branch frequency for code layout. |
| Extend opcodes: `SELECT` (ternary), `MEMCPY` (struct copy) | P2 | Planned | Reduces instruction count for complex expressions. |

### 6.6 C Backend

| Item | Priority | Status | Notes |
|---|---|---|---|
| Register allocation (instead of hoisting all IR results) | P2 | Planned | Reduce C local variable count; improve generated code quality. |
| Struct zero-initialization via designated initializers | P2 | Done | Already handled; verify consistency across backends. |
| C99 standard conformance audit | P2 | Planned | Ensure emitted C compiles cleanly under strict flags on all platforms. |

### 6.7 JVM Backend

| Item | Priority | Status | Notes |
|---|---|---|---|
| Local variable slot reuse | P2 | Planned | [`JvmLocalAllocator.java`](src/main/java/site/ilemon/backend/jvm/JvmLocalAllocator.java) — reduce local slot count. |
| Method inlining | P2 | Planned | Simple inlining for small methods; reduces call overhead. |
| Verify bytecode with `javap` in tests | P1 | Planned | Add verifier step to `JvmBackendTest`. |

### 6.8 Diagnostics

| Item | Priority | Status | Notes |
|---|---|---|---|
| Multi-error collection mode | P1 | Planned | Allow compilation to report all errors, not just first. |
| Suggestion engine for common mistakes | P2 | Planned | e.g., `did you mean...`, fix-it hints. |
| Diagnostic groups / suppressions | P3 | Planned | Allow users to suppress specific diagnostic categories. |

---

## 7. Testing Roadmap

### 7.1 Missing Tests

| Area | Gap | Priority | Suggested test class |
|---|---|---|---|
| StructARC | Struct ARC lifecycle in loops | P2 | Extend [`StructArcTest.java`](src/test/java/StructArcTest.java) |
| NullFlow | Null flow through struct pointer chains | P2 | New test class |
| MultiBackend | Float/double parity edge cases | P1 | Add cases to [`MultiBackendTest.java`](src/test/java/MultiBackendTest.java) |
| Constants | Constant expression in array size | P2 | [`GlobalConstTest.java`](src/test/java/GlobalConstTest.java) |
| Modules | Private struct field access via public function | P1 | [`ModuleStructScopeTest.java`](src/test/java/ModuleStructScopeTest.java) |
| Switch | Switch with enum subject and integer case (should fail) | P1 | [`SwitchTest.java`](src/test/java/SwitchTest.java) |
| Pointers | Pointer to struct field through multiple indirection | P2 | [`PointerTest.java`](src/test/java/PointerTest.java) |
| ARC | ARC with nested blocks and early return | P2 | [`ArcControlFlowTest.java`](src/test/java/ArcControlFlowTest.java) |

### 7.2 Regression Areas

| Area | Risk | Priority |
|---|---|---|
| Switch IR block ordering | High — physical block order is critical | P0 |
| Break/continue target resolution | High — innermost breakable wins | P0 |
| Module import recursion | Medium — new statement types may hide imports | P1 |
| C backend label deduplication | Medium — unreachable blocks still emit | P2 |
| JVM bytecode label patching | Medium — symbolic labels patched in `toBytecode()` | P2 |
| Null flow fixpoint convergence | Low — already tested in `NullSafetyFlowTest` | P3 |

### 7.3 C/JVM Parity

| Test | Coverage | Status |
|---|---|---|
| [`MultiBackendTest.java`](src/test/java/MultiBackendTest.java) | General parity | ✅ |
| [`PointerMultiBackendTest.java`](src/test/java/PointerMultiBackendTest.java) | Pointer-specific parity | ✅ |
| [`ModuleStructScopeTest.java`](src/test/java/ModuleStructScopeTest.java) | Module + struct parity | ✅ |
| [`NullSafetyFlowTest.java`](src/test/java/NullSafetyFlowTest.java) | Null safety parity | ✅ |
| [`OperatorTest.java`](src/test/java/OperatorTest.java) | Operator parity | ✅ |
| [`FullFeatureMatrixTest.java`](src/test/java/FullFeatureMatrixTest.java) | Systematic feature matrix | ✅ |
| [`NativeEndToEndTest.java`](src/test/java/NativeEndToEndTest.java) | Native C end-to-end | ✅ |
| [`LargeBenchmarkTest.java`](src/test/java/LargeBenchmarkTest.java) | Multi-module benchmark parity | ✅ |
| `examples/pointer_full/` | Full pointer feature parity | ✅ |
| `examples/full_feature_matrix/` | Full feature parity | ✅ |

### 7.4 End-to-End Tests

| Test | Scope | Status |
|---|---|---|
| [`AllExamplesJvmTest.java`](src/test/java/AllExamplesJvmTest.java) | All root examples via JVM backend | ✅ |
| [`CBackendTest.java`](src/test/java/CBackendTest.java) | C backend structural tests | ✅ |
| [`JvmBackendTest.java`](src/test/java/JvmBackendTest.java) | JVM backend structural tests | ✅ |
| [`NativeRuntimeSafetyTest.java`](src/test/java/NativeRuntimeSafetyTest.java) | C runtime safety checks | ✅ |
| [`LemonCCliTest.java`](src/test/java/LemonCCliTest.java) | CLI flag parsing and execution | ✅ |

### 7.5 Performance / Benchmark Coverage

| Benchmark | Status | Notes |
|---|---|---|
| `examples/large_benchmark/` (6-module world sim) | ✅ | Parity verified; no perf regression baseline. |
| `examples/full_feature_matrix/` (13 matrix tests) | ✅ | Systematic feature coverage. |
| `examples/pointer_full/` (12 pointer tests) | ✅ | Pointer feature matrix. |
| Benchmark regression suite | ❌ | No automated perf benchmarking. |
| Compiler compilation time measurement | ❌ | Not tracked. |

---

## 8. Agent Workflow

Follow this workflow for all tasks to minimize token usage and repository scanning:

1. **Read [`AGENTS.md`](AGENTS.md)** — project overview, pipeline, coding conventions.
2. **Read relevant [`ROADMAP.md`](ROADMAP.md) section** — current status, planned items, known bugs.
3. **Read relevant [`guide.md`](guide.md) section** — architecture details, per-stage guidance.
4. **Search relevant symbols** — use `search_files` / `grep` to find affected code before opening large files.
5. **Inspect affected files only** — do not read entire files unless necessary.
6. **Implement** — make targeted changes; update only affected sections.
7. **Run targeted tests** — `mvn -Dtest=<Class> test`.
8. **Run `mvn test`** — verify no regressions.
9. **Update roadmap** — only the sections affected by the change.

### Rules

- **Do not rescan the entire repository** unless necessary.
- **Do not reread unchanged large files** — rely on previous context.
- **Search symbols before opening large source files**.
- **Prefer targeted context over full-file context**.
- **Update only the roadmap sections affected by a change**.
- **Keep entries concise and factual**.
- **Remove obsolete roadmap items**.
- **Move completed items out of active work**.
- **Never mark work complete without implementation/test evidence**.
- **Never modify source code, tests, or expected outputs as part of this documentation task**.

---

*Roadmap last updated: 2026-10-04. Verified against source, tests, examples, and project structure.*
