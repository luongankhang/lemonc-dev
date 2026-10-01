# LemonC — Agent Guide

Verified against source on 2026-10-01 (`src/main/java/site/ilemon`, Java 21, Maven, JUnit 4.13.2, artifact `LemonC-0.1-beta`).

## What LemonC is

Single-pass-pipeline compiler for the Lemon language (C-like syntax) with two backends:

```
Lexer → Parser → Semantic → AstOptimizer → AstToIrLowerer (LemonIR) → { C backend, JVM backend }
                                     ↘ ARC (OwnershipAnalyzer on IR) ↗
```

Entry point: `site.ilemon.compiler.LemonC` (main class in assembly jar).

## Pipeline stages (all verified)

| Stage | Key files | Notes |
|---|---|---|
| Lexer | `lexer/Lexer.java`, `TokenKind.java` | Keyword table in static `KEYWORDS` map. `:` is `TokenKind.Colon`. Recovery via `LexException`. |
| Parser | `parser/Parser.java` | Recursive descent, `parseStmt()` dispatch by `look.kind`. Statement starts listed in `isStatementStart()`. Errors via `error(...)` + `synchronizeToStatementBoundary()`. Enum parsing: `parseEnumDecl()`, gated by `isEnumStart()` lookahead. Switch parsing: `parseSwitchStmt()` — case bodies parsed via `parseSwitchCaseBody()` (statement-list shape, no `{}` needed). |
| AST | `ast/Ast.java` | `Stmt.T` abstract base with `lineNum`, `span`, `accept(ISemanticVisitor)`. Non-sealed node classes per statement kind. `Ast.Type.Enum` wraps a name resolved to `Ast.EnumDecl` in semantic. |
| Semantic | `semantic/SemanticVisitor.java`, `MethodVarTable.java` | Single visitor implementing all `visit(Ast.Xxx)` methods. Local scopes: `mTable.enterScope()/exitScope()` + `currMethodLocalVar` set snapshots for "may be used before assignment". Loop depth via `loopDepth` counter. Diagnostics: `typeError` (E3xxx), `semanticError` (E2xxx), `error` (plain). |
| Optimizer | `optimizer/AstOptimizer.java` | Const-folding on expressions, dead branch elimination. **Must recursively handle every `Stmt` kind** or nodes silently pass through. |
| IR | `ir/AstToIrLowerer.java`, `IrInstruction.java` | `IrInstruction(Op op, IrValue result, List<IrValue> operands, String target)`. Opcodes: `CONST, ADD, SUB, MUL, DIV, REM, AND, OR, XOR, CMP, CONVERT, LOAD, STORE, ALLOC, ADDRESS_OF, CALL, RETURN, BRANCH, COND_BRANCH, PHI, BOUNDS_CHECK, EXTERNAL_CALL, BIT_NOT, FIELD_LOAD, FIELD_STORE, STRUCT_COPY, STRUCT_ZERO`. Terminators: `RETURN | BRANCH | COND_BRANCH`. Block labels are strings (`"while_cond_1"`). CFG per method in `MethodLoweringContext` (inner class of `AstToIrLowerer`) with `loopStack: Deque<LoopContext>`, `scopeStack: Deque<LexicalScope>`, `switchStack: Deque<BasicBlock>` (innermost breakable wins), `createBlock(prefix)`, `startBlock(b)`, `emit(inst)` (skips after terminator), `isTerminated(b)`. `LoopContext(breakTarget, continueTarget, forScope, bodyScope)`. `lowerSwitch` emits dispatch-chain + case-body blocks with fallthrough-linked BRANCHes; **no new IR opcode**. ARC ops (`RETAIN`/`RELEASE` emitted via `emitRetain`/`emitRelease` helpers) live only in this lowering — there is **no separate IR for backends**. |
| ARC | `arc/OwnershipAnalyzer.java`, `RefcountSimulator.java` | Walks IR statements (`Ast.Stmt.While`, `For`, `Break` handled in `emitStatement`). Ownership blocks per CFG region. |
| Null flow | `flow/NullFlowAnalyzer.java` | Pointer nullability dataflow; fixpoint loops for `While`/`For`. |
| C backend | `backend/c/CBackend.java`, `CFunctionEmitter.java`, `CInstructionEmitter.java`, `CTypeEmitter.java` | Emits readable C per IR function: hoists all instruction results as locals, emits `goto <label>` for `BRANCH`/`COND_BRANCH`, labels as `<name>:;`. Enum typedefs via `CTypeEmitter.emitEnumTypedefs` (`LemonC_<Name>` tags). Runtime: `runtime/lemon_runtime.c` (`lemon_panic_divzero`, `lemon_require_ptr`, `lemon_bounds_check`, `lemon_array_at`, `lemon_alloc`). |
| JVM backend | `backend/jvm/JvmBackend.java`, `JvmMethodEmitter.java`, `JvmInstructionEmitter.java`, `JvmCodeBuilder.java` | Lowers IR CFG to JVM labels + jumps. `JvmCodeBuilder` is the only encoder: `label(name)`, `branch(opcode, target)`, symbolic labels patched in `toBytecode()`. ARC calls dropped (GC). Enum values inlined as ints. |
| Diagnostics | `diagnostic/DiagnosticCodes.java` | Stable codes E0001–E9001. New codes: add a constant here, keep the `E[0-9]{4}` pattern. |
| Modules | `compiler/ModuleLoader.java` | Import merging; re-exports public enum members as `alias_NAME` constants. Traverses statements only for imports — extend if a new statement can contain `import`. |

## Coding rules (from repo conventions)

- AST nodes: mutable POJOs, `accept()` delegates to `ISemanticVisitor`; always set `lineNum` and `setSpan(...)` when the parser creates a node.
- Visitor updates: adding an `Ast.Stmt.Xxx` node requires updating **every** implementor of `ISemanticVisitor` (currently `SemanticVisitor`, `MethodCallRewriter`, `AstOptimizer`, plus any others — search `visit(Ast.Stmt.`).
- IR lowering: no backend-specific IR. Emit only shared opcodes; `PHI` must never reach backends.
- C backend must compile clean under `-Wall -Wextra -Werror` (label/unused suppression logic in `CFunctionEmitter`).
- JVM backend: `JvmCodeBuilder` is the only place knowing binary encoding; do not emit raw bytes elsewhere.
- Diagnostics: prefer `typeError`/`semanticError` with codes over plain `error(...)`; include `span` where available.
- Local-variable scope in semantic: mirror `visit(Ast.Stmt.For)` — `mTable.enterScope()` before body, snapshot/restore `currMethodLocalVar`, `exitScope()` after.

## Test commands

```
mvn test                  # full suite (JUnit 4)
mvn -Dtest=SwitchTest test   # one class
mvn -Dtest=EnumTest test       # enum suite
mvn -Dtest=ReturnPathAnalysisTest test  # return-path analysis
```

Test conventions: JUnit 4 classes in `src/test/java`; multi-backend end-to-end tests exist (e.g. `MultiBackendTest`, `JvmBackendTest`, `CBackendTest`, `EnumTest`, `AllExamplesJvmTest`); diagnostic assertions via `DiagnosticTestSupport`/`DiagnosticEngine`. Examples live in `examples/*.lemon` and are exercised by `AllExamplesJvmTest`.

## Adding a language feature — checklist

1. **Lexer**: add keyword to `KEYWORDS` in `Lexer.java` + `TokenKind` entry if needed.
2. **Parser**: extend `parseStmt()` dispatch + `isStatementStart()`; set spans; recovery-safe (`synchronizeToStatementBoundary`).
3. **AST**: new `Stmt.T` subclass with `accept()`.
4. **Semantic**: new `visit` in `SemanticVisitor` (+ every other visitor implementor). Handle scoping (mTable, `currMethodLocalVar`), flow (`statementTerminates`), type checks.
5. **AstOptimizer**: recursive case in `optimizeStmt`.
6. **AstToIrLowerer**: lower to shared IR in `lowerStmt`; push/pop `LoopContext`/scopes for any construct that `break`/`continue` can target.
7. **ARC**: extend `OwnershipAnalyzer.emitStatement` if the construct can release/retain.
8. **Null flow**: extend `NullFlowAnalyzer.analyzeStmt` if nullability can change.
9. **C + JVM backends**: only if new opcodes were introduced; otherwise no backend change.
10. **Tests**: positive/negative + multi-backend end-to-end; **examples** in `examples/` if user-visible.
11. **Docs**: update `AGENTS.md`/`guide.md`.

## Points of caution

- `break`/`continue` currently require `loopDepth > 0 || switchDepth > 0` in semantic (`visit(Ast.Stmt.Break)`); IR break resolution: innermost breakable wins — if a loop was pushed inside a switch (`loopStack.peek().breakTarget == switchStack.peek()`), the loop target is used; otherwise the switch target. A new breakable construct must extend **both**.
- `AstOptimizer` silently drops unknown `Stmt` kinds (falls through to `return stmt`) — verify by reading, not by trusting.
- `ModuleLoader.collectStatementImports` recurses into `Block`/`If`/`While`/`For`/`Switch` (case bodies included).
- C backend labels: blocks with no incoming branch get no label (dedup in `CFunctionEmitter`); unreachable trailing blocks still emit.
- Enum member constants are re-exported on import as `alias_NAME` (`ModuleLoader`); enum type checks use `resolveEnum` with module alias support.
- Switch IR layout is order-sensitive: `MethodLoweringContext.emit()` skips instructions after a terminator, so dispatch-chain linking relies on **physical block order** (`[entry+subject, dispatches..., default, bodies..., exit]`), spliced via `ctx.blocks` list manipulation in `lowerSwitch`. Changing block creation order breaks dispatch.
- Return-path analysis (`flowOfStatement`) models switch fallthrough: a clause without a trailing terminator inherits the next clause's termination (computed back-to-front); a clause ending in `break`/`continue` never returns by itself. A switch returns on all paths only when every clause terminates AND a `default` exists.
- `case` labels: `resolveSwitchCaseLabel` resolves int literals/negative forms (`integralLiteralValue`), enum members (`Color.RED` via `resolveEnum`, bare `ADD_OP` via `enumMemberTable`), and integer named constants. Enum labels are rejected on integer subjects and vice versa.
