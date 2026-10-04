# LemonC Compiler Development Guide

Verified against source on 2026-10-04. Companion to `AGENTS.md` (architecture summary + coding rules). This guide walks a language feature through every pipeline stage with concrete code references.

## 1. Architecture and pipeline

```
source.lemon
   │
   ▼
Lexer (lexer/Lexer.java)            → tokens (TokenKind)
   │   KEYWORDS map: "switch"→Switch, "case"→Case, "default"→Default,
   │   "break"→Break, "continue"→Continue, "struct"→Struct, "enum"→Enum,
   │   "const"→Const, "pub"→Pub, "import"→Import, "null"→Null
   ▼
Parser (parser/Parser.java)         → Ast.Program (ast/Ast.java)
   │   parseStmt() dispatch by look.kind; isStatementStart() lists all statement starts
   │   Recovery: parseStmt() errors → synchronizeToStatementBoundary()
   ▼
SemanticVisitor (semantic/SemanticVisitor.java)
   → typed AST + diagnostics (E2xxx semantic, E3xxx type)
   ▼
AstOptimizer (optimizer/AstOptimizer.java)
   → const-folded AST, dead branch elimination
   ▼
AstToIrLowerer (ir/AstToIrLowerer.java)
   → IrModule (LemonIR: BasicBlock + IrInstruction)
   ▼
OwnershipAnalyzer (arc/OwnershipAnalyzer.java)
   → ARC verdicts; RETAIN/RELEASE emitted during lowering
   ▼
NullFlowAnalyzer (flow/NullFlowAnalyzer.java)
   → pointer nullability dataflow (fixpoint for loops)
   ▼
┌──────────────────┬────────────────────────┐
│ C backend        │ JVM backend            │
│ backend/c/       │ backend/jvm/           │
│ CFunctionEmitter │ JvmMethodEmitter       │
│ CInstructionEmit │ JvmInstructionEmitter  │
│ CTypeEmitter     │ JvmCodeBuilder (binary │
│ CModuleEmitter   │  encoder only)         │
│ ConstantProp     │ JvmLocalAllocator      │
│ DeadStoreElim    │ JvmStackTracker        │
│ NativeToolchain  │ JvmTypeMapper          │
│                  │ JvmClassWriter         │
│                  │ JvmMethod              │
└──────────────────┴────────────────────────┘
```

One shared IR; backends never see a second IR. `PHI` must be resolved before backends.

## 2. Source file map

### Lexer
- `src/main/java/site/ilemon/lexer/Lexer.java` — token stream, static `KEYWORDS` map (63 keywords including `struct`, `enum`, `const`, `pub`, `import`, `null`)
- `src/main/java/site/ilemon/lexer/TokenKind.java` — enum of all token kinds
- `src/main/java/site/ilemon/lexer/Token.java` — token record (kind, lexeme, line, col)
- `src/main/java/site/ilemon/lexer/LexerState.java` — DFA state machine for tokenization

### Parser
- `src/main/java/site/ilemon/parser/Parser.java` — recursive descent
  - `parseStmt()` — dispatch by `look.kind`
  - `isStatementStart()` — list of statement-starting tokens
  - `synchronizeToStatementBoundary()` — error recovery
  - `parseEnumDecl()` — gated by `isEnumStart()` lookahead
  - `parseSwitchStmt()` — case bodies via `parseSwitchCaseBody()` (statement list, no `{}`)

### AST
- `src/main/java/site/ilemon/ast/Ast.java`
  - `Stmt.T` — abstract base; mutable POJO with `lineNum` + `span`
  - Subclasses: `Assign`, `Block`, `If`, `While`, `For`, `Switch`, `CaseClause`, `Break`, `Continue`, `Return`, `Printf`, `PrintLine`, `Call`, `ArrayAssign`, `DerefAssign`, `Import`, `VarDecl`, `FieldAssign`, `ExprStmt`
  - `Expr.T` — abstract base; `Number`, `Id`, `Add/Sub/Mul/Div/Mod`, `And/Or/Not`, `GT/LT/GTE/LTE/EQ/NEQ`, `Call`, `ArrayAccess`, `ArrayLength`, `Field`, `AddressOf`, `Deref`, `Null`, `True`, `False`, `Str`, `InitializerList`, `PreInc/PostInc/PreDec/PostDec`, `UnaryPlus/UnaryMinus/BitNot`, `Ternary`
  - `Type.T` — **sealed** abstract base; `TypeKind` enum (INT, FLOAT, DOUBLE, BOOL, CHAR, BYTE, SHORT, LONG, STRING, VOID, INT_ARRAY … POINTER, STRUCT, ENUM, STRUCT_ARRAY, NULL)
  - Subclasses: `Int`, `Float`, `Double`, `Str`, `Bool`, `Char`, `Byte`, `Short`, `Long`, `Void`, `IntArray` … `StringArray`, `StructArray`, `Pointer`, `Struct`, `Enum`, `Null`
  - `Declare.T` — abstract base; `DeclareSingle` (type, id, initExp, visibility)
  - `StructDecl` — name, fields, visibility, declaringModule
  - `EnumDecl` — name, members, visibility, declaringModule
  - `ConstDecl` — global immutable constant (literal initializer only)
  - `ImportDecl` — module import

### Semantic
- `src/main/java/site/ilemon/semantic/SemanticVisitor.java` — main visitor
- `src/main/java/site/ilemon/semantic/MethodVarTable.java` — local variable scope table
- `src/main/java/site/ilemon/semantic/ScopeManager.java` — scope nesting for imports
- `src/main/java/site/ilemon/semantic/Symbol.java` — symbol table entry (record: name, type, kind, lineNumber)
- `src/main/java/site/ilemon/semantic/ImportSymbol.java` — imported symbol

### Optimizer
- `src/main/java/site/ilemon/optimizer/AstOptimizer.java` — `optimizeStmt()`, `optimizeExpr()`, `boolValue()`
  - Falls through to `return stmt` for unknown `Stmt` kinds — verify by reading

### IR
- `src/main/java/site/ilemon/ir/IrInstruction.java` — record `Op` enum (CONST, ADD, SUB, MUL, DIV, REM, AND, OR, XOR, CMP, CONVERT, LOAD, STORE, ALLOC, ADDRESS_OF, CALL, RETURN, BRANCH, COND_BRANCH, PHI, BOUNDS_CHECK, EXTERNAL_CALL, BIT_NOT, FIELD_LOAD, FIELD_STORE, STRUCT_COPY, STRUCT_ZERO)
- `src/main/java/site/ilemon/ir/IrModule.java` — top-level IR unit with struct/enum registries
- `src/main/java/site/ilemon/ir/IrFunction.java` — function signature + blocks
- `src/main/java/site/ilemon/ir/BasicBlock.java` — ordered list of instructions
- `src/main/java/site/ilemon/ir/IrType.java` — type kind for IR
- `src/main/java/site/ilemon/ir/IrValue.java` — SSA value
- `src/main/java/site/ilemon/ir/IrVerifier.java` — structural verifier for LemonIR
- `src/main/java/site/ilemon/ir/ArcOptimizer.java` — eliminates redundant retain/release pairs and dead stores
- `src/main/java/site/ilemon/ir/AstToIrLowerer.java` — CFG construction via `MethodLoweringContext` (inner class)
  - `loopStack: Deque<LoopContext>`, `scopeStack: Deque<LexicalScope>`, `switchStack: Deque<BasicBlock>`
  - `createBlock(prefix)`, `startBlock(b)`, `emit(inst)` (skips after terminator), `isTerminated(b)`
  - `LoopContext(breakTarget, continueTarget, forScope, bodyScope)`
  - `lowerSwitch` emits dispatch-chain + case-body blocks with fallthrough-linked BRANCHes; **no new IR opcode**

### ARC
- `src/main/java/site/ilemon/arc/OwnershipAnalyzer.java` — walks IR, emits RETAIN/RELEASE
- `src/main/java/site/ilemon/arc/RefcountSimulator.java` — refcount simulation
- `src/main/java/site/ilemon/arc/OwnershipIr.java` — analysis result
- `src/main/java/site/ilemon/arc/OwnershipBlock.java` — CFG ownership region
- `src/main/java/site/ilemon/arc/OwnershipFunction.java` — per-function ownership info
- `src/main/java/site/ilemon/arc/MemoryOp.java` — retain/release op

### Null flow
- `src/main/java/site/ilemon/flow/NullFlowAnalyzer.java` — fixpoint dataflow for pointer nullability
- `src/main/java/site/ilemon/flow/NullFlowResult.java` — analysis output
- `src/main/java/site/ilemon/flow/Nullability.java` — UNKNOWN / NON_NULL / maybe-null lattice

### C backend
- `src/main/java/site/ilemon/backend/c/CBackend.java` — orchestrates C emission
- `src/main/java/site/ilemon/backend/c/CFunctionEmitter.java` — per-function C code, local hoisting, `-Wall -Wextra -Werror` label/unused suppression
- `src/main/java/site/ilemon/backend/c/CInstructionEmitter.java` — instruction → C code
- `src/main/java/site/ilemon/backend/c/CTypeEmitter.java` — type mapping, `emitEnumTypedefs` (`LemonC_<Name>` tags)
- `src/main/java/site/ilemon/backend/c/CModuleEmitter.java` — emits entire C translation unit (includes + types + functions)
- `src/main/java/site/ilemon/backend/c/ConstantPropagation.java` — compile-time constant folding
- `src/main/java/site/ilemon/backend/c/DeadStoreElimination.java` — DCE pass
- `src/main/java/site/ilemon/backend/c/NativeToolchain.java` — C compiler invocation (gcc/clang discovery)

### JVM backend
- `src/main/java/site/ilemon/backend/jvm/JvmBackend.java` — orchestrates JVM emission
- `src/main/java/site/ilemon/backend/jvm/JvmMethodEmitter.java` — per-method bytecode
- `src/main/java/site/ilemon/backend/jvm/JvmInstructionEmitter.java` — instruction → JVM opcode
- `src/main/java/site/ilemon/backend/jvm/JvmCodeBuilder.java` — **only** encoder knowing binary encoding (`label(name)`, `branch(opcode, target)`, symbolic labels patched in `toBytecode()`)
- `src/main/java/site/ilemon/backend/jvm/JvmLocalAllocator.java` — local slot allocation
- `src/main/java/site/ilemon/backend/jvm/JvmStackTracker.java` — stack depth tracking
- `src/main/java/site/ilemon/backend/jvm/JvmTypeMapper.java` — type descriptor mapping
- `src/main/java/site/ilemon/backend/jvm/JvmClassWriter.java` — .class file writer
- `src/main/java/site/ilemon/backend/jvm/JvmMethod.java` — method container

### Diagnostics
- `src/main/java/site/ilemon/diagnostic/DiagnosticCodes.java` — stable E0001–E9001 constants
- `src/main/java/site/ilemon/diagnostic/DiagnosticEngine.java` — collects diagnostics
- `src/main/java/site/ilemon/diagnostic/DiagnosticRenderer.java` — human-readable output with source snippets
- `src/main/java/site/ilemon/diagnostic/DiagnosticJsonExporter.java` — JSON export
- `src/main/java/site/ilemon/diagnostic/Severity.java` — ERROR / WARNING / NOTE
- `src/main/java/site/ilemon/diagnostic/Diagnostic.java` — diagnostic record
- `src/main/java/site/ilemon/diagnostic/DiagnosticBuilder.java` — fluent builder
- `src/main/java/site/ilemon/diagnostic/DiagnosticLabel.java` — label metadata
- `src/main/java/site/ilemon/diagnostic/DiagnosticSuggestion.java` — fix suggestion
- `src/main/java/site/ilemon/diagnostic/SourceLineProvider.java` — source line lookup
- `src/main/java/site/ilemon/diagnostic/TypeDiagnosticContext.java` — type context for diagnostics

### Modules
- `src/main/java/site/ilemon/compiler/ModuleLoader.java` — import merging, `alias_NAME` re-exports, `collectStatementImports` recurses into Block/If/While/For/Switch
- `src/main/java/site/ilemon/compiler/MethodCallRewriter.java` — rewrites method calls for imported modules (implements ISemanticVisitor)
- `src/main/java/site/ilemon/compiler/LemonC.java` — entry point (main class)
- `src/main/java/site/ilemon/compiler/AstPrinter.java`, `IrPrinter.java` — debug printers

### Visitor interface
- `src/main/java/site/ilemon/visitor/ISemanticVisitor.java` — **only 2 implementors**: `SemanticVisitor`, `MethodCallRewriter`
  - Default methods for newer visitors: `visit(Ast.Expr.PreInc)`, `visit(Ast.Expr.PostInc)`, `visit(Ast.Expr.PreDec)`, `visit(Ast.Expr.PostDec)`, `visit(Ast.Expr.UnaryPlus)`, `visit(Ast.Expr.UnaryMinus)`, `visit(Ast.Expr.BitNot)`, `visit(Ast.Expr.Ternary)`, `visit(Ast.Type.Enum)`, `visit(Ast.Stmt.ExprStmt)`

### Type rules
- `src/main/java/site/ilemon/type/TypeRules.java` — type compatibility helpers

### Utils
- `src/main/java/site/ilemon/util/SourceSpan.java` — source position span
- `src/main/java/site/ilemon/list/DoublyLinkedList.java` — linked list utility

### Exceptions
- `src/main/java/site/ilemon/exception/CompilerException.java`, `LexException.java`, `ParseException.java`, `SemanticException.java`

## 3. How a feature flows through the pipeline

### 3.1 Lexer

Add keywords in `Lexer.java` static `KEYWORDS` map (`"while" → TokenKind.While` pattern). New punctuation → new `TokenKind` entry. Lexer errors use `lexicalError(...)` / `LexException`.

### 3.2 Parser

`parseStmt()` is a long `if/else if` chain on `look.kind`. A new statement needs:

1. A branch in `parseStmt()` — `match()` keywords/delimiters, build the AST node, set `lineNum`/`span` via `tokenSpan(token)`.
2. A line in `isStatementStart()` so statement-block parsing picks it up.
3. Recovery safety: parser errors call `error(...)` and the loop in `parseStmts()` catches `ParseException` and calls `synchronizeToStatementBoundary()`.

Nested statements: `While`/`For` recurse with `parseStmt()`; block-scoped bodies use `parseStmts()` inside `{ }`.

### 3.3 AST

New node in `ast/Ast.java` under `Stmt`:

```java
public static class MyStmt extends T {
    // fields + getters/setters
    public MyStmt(int lineNum) { this.setLineNum(lineNum); }
    @Override
    public void accept(ISemanticVisitor v) { v.visit(this); }
}
```

Base class `Stmt.T` carries `lineNum` + `span`.

### 3.4 Semantic

Implement `visit(MyStmt)` in `SemanticVisitor` (and any other `ISemanticVisitor` implementor — search `visit(Ast.Stmt.`). Conventions:

- **Scoping**: for a new block-like construct, mirror `visit(Ast.Stmt.For)`: `mTable.enterScope()` before body, snapshot `currMethodLocalVar` (`HashSet<String> before = new HashSet<>(...)`), restore after, `mTable.exitScope()`.
- **Type checks**: `this.visit(expr)` sets `this.currType`; then `typeError(DiagnosticCodes.TYPE_*, expected, actual, expressionName(...), lineNum, span, label, suggestion)`.
- **Loop depth**: `loopDepth++/--` around bodies so `visit(Ast.Stmt.Break)` (requires `loopDepth > 0`) works.
- **Flow**: `statementTerminates(stmt)` returns true for `Return`/`Break`/`Continue`; extend for new control flow if it affects "may be used before assignment" or return-path analysis (`ReturnPathAnalysisTest`).
- **Enums**: `enumTable` (name → `Ast.EnumDecl`), `enumMemberTable` (both `"Name.MEMBER"` and bare `"MEMBER"` keys), `resolveEnum(name)` with module alias support. Enum member expressions type as `Ast.Type.Enum`.

### 3.5 AstOptimizer

Add a recursive case in `optimizeStmt()`:

```java
if (stmt instanceof Ast.Stmt.MyStmt myStmt) {
    return new Ast.Stmt.MyStmt(/* optimized children */, myStmt.getLineNum());
}
```

**Caution**: unknown `Stmt` kinds fall through to `return stmt` (unchanged) — verify coverage by reading the chain, not by trusting.

### 3.6 LemonIR lowering (AstToIrLowerer)

`lowerStmt(stmt, ctx)` dispatches on `instanceof`. CFG primitives on `MethodLoweringContext`:

- `ctx.createBlock("prefix")` → unique `BasicBlock` (`"prefix_N"`)
- `ctx.startBlock(b)` → sets `currentBlock`
- `ctx.emit(inst)` → appends unless block already terminated
- `ctx.isTerminated(b)` → last instruction is a terminator
- `ctx.loopStack.push(new LoopContext(breakTarget, continueTarget, forScope, bodyScope))` — break/continue targets
- Scopes: `ctx.pushScope(ScopeKind.X)` / `popScope()` / `releaseScope(scope)` / `releaseScopesUpTo(scope)` (used by break to release loop-locals)

Pattern for a branch statement (from `Ast.Stmt.If` lowering):

```java
IrValue cond = lowerExpr(condExpr, ctx);
BasicBlock thenB = ctx.createBlock("x_then");
BasicBlock mergeB = ctx.createBlock("x_merge");
ctx.emit(new IrInstruction(IrInstruction.Op.CMP, notCond, List.of(cond, new IrValue("0", cond.type())), "=="));
ctx.emit(new IrInstruction(IrInstruction.Op.COND_BRANCH, null, List.of(notCond), thenB.name()));
ctx.startBlock(thenB);
/* lower body */
if (!ctx.isTerminated(ctx.currentBlock)) ctx.emit(BRANCH → mergeB.name());
ctx.startBlock(mergeB);
```

Pattern for a breakable construct (from `While` lowering): push `LoopContext` before lowering body, pop after, and in `lowerStmt`'s `Break` case read `ctx.loopStack.peek()`.

**For a new breakable construct**: `Break` handling in `AstToIrLowerer` currently only knows loops — it must be extended to use the new construct's break target (e.g. a separate `switchStack` or a shared break-target stack).

### 3.7 ARC

`arc/OwnershipAnalyzer.emitStatement` walks IR statements (`While`, `For`, `Break` handled). If the new construct can own/release memory (managed locals in its scope), mirror the `While` handling: build `OwnershipBlock` regions for cond/body/exit and release on break paths.

### 3.8 C backend

No change needed unless new opcodes were introduced. `CFunctionEmitter.emit` hoists every instruction result as a C local and emits block labels `safe(block.name()):;` (only for blocks that are branch targets) + instructions via `CInstructionEmitter.emit`. `BRANCH` → `goto label;`, `COND_BRANCH` → `if (cond) goto label;`. Enum types emit via `CTypeEmitter` (`LemonC_<Name>`). Clean under `-Wall -Wextra -Werror`.

### 3.9 JVM backend

No change needed unless new opcodes were introduced. `JvmMethodEmitter.emit` labels every block (`code.label(block.name())`) and emits instructions; `JvmCodeBuilder.toBytecode()` patches branch offsets. Enum values are inlined as int constants.

### 3.10 Tests

- Positive/negative unit tests (`src/test/java`, JUnit 4).
- Multi-backend end-to-end: compile the same `.lemon` through C and JVM, compare output (pattern: `MultiBackendTest`, `PointerMultiBackendTest`).
- Diagnostics: assert codes/messages via `DiagnosticEngine`/`DiagnosticTestSupport`.
- Examples: add to `examples/*.lemon`; `AllExamplesJvmTest` exercises them.

## 4. Worked example: `switch/case/default` (fully implemented)

Syntax:

```
switch (value) {
    case 1: printf("one\n"); break;
    case 2: printf("two\n");
    default: printf("other\n");
}
```

Stage-by-stage:

| Stage | Work |
|---|---|
| Lexer | `"switch"`, `"case"`, `"default"`, `"break"` keywords in `KEYWORDS`. `:` already exists (`TokenKind.Colon`). |
| Parser | `parseStmt()` branch for `switch`: `match("switch")`, `match("(")`, `parseExpr()`, `match(")")`, `match("{")`, then loop parsing `case <const-expr>: <stmts>` / `default:` until `match("}")`. Case bodies are statement lists (no `{}` needed). Add `TokenKind.Switch`/`Case`/`Default` to `isStatementStart()`. |
| AST | `Ast.Stmt.Switch { Expr.T subject; List<CaseClause> cases; }` + `CaseClause { Expr.T label (null = default); List<Stmt.T> body; int lineNum; }`. |
| Semantic | `visit(Ast.Stmt.Switch)`: type-check subject (int-family or enum); each case label must be a constant of a compatible type; reject duplicate case values (set of resolved ints) and more than one `default`; enter a scope per switch (mirror `For`); increment a break depth that `visit(Ast.Stmt.Break)` accepts (loop **or** switch); type-check each case body. |
| AstOptimizer | Recursive case: optimize subject + case labels + bodies. |
| LemonIR | Lower to shared opcodes: `switch_cond` block evaluating subject into a temp, `COND_BRANCH`/`CMP` chain per case (or `case_body_i` blocks), `BRANCH` to bodies, default block, `switch_exit` block. Push a break context whose break target is `switch_exit`; body scopes pushed per case so `break` releases case-locals. **No new IR opcode** — reuse `CMP`/`COND_BRANCH`/`BRANCH`. |
| ARC | `Break` already handled; case-local managed vars released via scope release in lowering. |
| C backend | No change (gotos + labels only). |
| JVM backend | No change (labels + GOTO only). |
| Tests | positive + negative, enum + int, nested switch, switch in loop, fallthrough, break, default, duplicate case/default, scope, C/JVM end-to-end. |
| Examples | `examples/switch_showcase.lemon`. |

## 5. Semantics decisions (binding)

- **Fallthrough**: no `break` at the end of a case falls through into the next case body (C semantics). IR: case body without `break` gets a trailing `BRANCH` to the next case body's label, not to `switch_exit`.
- **Duplicate case**: semantic error `E2003` (SEM_DUPLICATE_DECLARATION) with a clear message naming the value.
- **Duplicate default**: semantic error, at most one `default` clause.
- **Case must be constant**: labels restricted to integer literals / enum members / named constants that resolve to integers.
- **break inside switch**: exits the innermost switch (or loop if inside a loop body inside a switch — innermost wins).
- **Case variable scope**: declarations inside a case body are scoped to that case (consistent with the language's block scoping); `break` releases them.
- **Arrays**: fixed-size at declaration, dynamic via `new int[n]`. `.length` property yields `int`. Element access `arr[i]` checked with `BOUNDS_CHECK` IR opcode.
- **Structs**: value semantics by default; `new StructName()` allocates. Field access via `.` (value) or `->` (pointer). Struct copy via `STRUCT_COPY` opcode.
- **Enums**: backing type is `int`. `enum X` values are `@enum X` type during semantic, inlined as int in both backends.
- **Pointers**: `&x` requires `x` to be a modifiable lvalue (local, field, array element, deref). Returning a pointer to a local is a semantic error (`E2008` SEM_POINTER_ESCAPE). Deref of non-pointer is a type error (`E3010`). Pointer arithmetic limited to `+`/`-` with `int`.
- **ARC**: managed types are struct values and struct arrays. Pointers do not participate in refcounting (they are borrowed). Unmanaged locals (int, float, bool, string) are not retained/released.
- **Constants**: `const int X = 5;` only at global scope with literal initializer. Referenced by name in expressions; resolved at semantic time and inlined as `CONST` in IR.
- **Return**: `return` without expr in void method, or `return expr` in non-void method. Return-path analysis in `NullFlowAnalyzer` determines if all paths return.
- **`.length`**: valid only on array expressions; resolved to `ArrayLength` AST node; lowered to `LOAD` of array length field.

## 6. Testing strategy

### Unit tests
Each conceptual area has a focused test class in `src/test/java/`:
- `ParserTest`, `SemanticTest`, `AstOptimizerTest` — stage-specific
- `SwitchTest`, `EnumTest`, `StructTest`, `PointerTest`, `ArrayLengthTest` — feature-specific
- `NullSafetyFlowTest`, `ArcOwnershipTest`, `ArcControlFlowTest`, `ArcDiagnosticTest` — analysis-specific
- `ReturnPathAnalysisTest` — return-path verification
- `DiagnosticTest`, `DiagnosticEngineTest`, `SemanticDiagnosticTest` — error reporting
- `UninitializedVariableCfgTest` — definite assignment CFG analysis

### End-to-end tests
- `AllExamplesJvmTest` — compiles every `.lemon` in `examples/` through the JVM backend
- `CBackendTest` — C backend compilation tests
- `JvmBackendTest` — JVM backend tests
- `MultiBackendTest` — same source compiled through both backends, output compared
- `PointerMultiBackendTest` — pointer-specific parity tests
- `LargeBenchmarkTest` — 6-module benchmark (world sim with structs, enums, pointers)
- `FullFeatureMatrixTest` — systematic feature coverage
- `NativeEndToEndTest`, `NativeRuntimeSafetyTest` — C-native end-to-end with runtime checks
- `LemonCCliTest` — CLI wrapper test
- `PointerShowcaseTest` — pointer showcase integration test
- `StructArcTest` — struct ARC tests

### Diagnostic assertions
Use `DiagnosticTestSupport` helpers to assert specific diagnostic codes and messages. Pattern:
```java
assertDiagnostics(compilerResult, DiagnosticCodes.SEM_DUPLICATE_DECLARATION);
```

### C/JVM parity requirements
- Every feature tested in both backends should have a parity test (e.g. `MultiBackendTest` cases).
- Output comparison: run both backends on the same `.lemon`, execute both, compare stdout.
- When adding a feature, add at least one multi-backend test case unless the feature is purely diagnostic.

## 7. Common pitfalls and regression rules

1. **AST node without visitor update**: adding a new `Stmt` subclass without updating `ISemanticVisitor` and all implementors causes a compile error (missing method). Always search `visit(Ast.Stmt.` to confirm coverage.
2. **AstOptimizer fallthrough**: unknown `Stmt` kinds silently pass through. Verify by reading the `optimizeStmt` chain.
3. **Switch IR block order**: `lowerSwitch` relies on physical block order in `ctx.blocks`. Do not reorder block creation without updating the splicing logic.
4. **break/continue target resolution**: innermost breakable wins. Extending this to a new construct requires updating both semantic (`switchDepth` or equivalent) and IR (`switchStack` or equivalent).
5. **Loop context stack**: `loopStack` is shared for all breakable constructs. If a new construct is breakable, it must push/pop a `LoopContext` or extend the existing mechanism.
6. **Scope snapshots**: `currMethodLocalVar` set must be snapshot/restored in semantic to avoid leaking "may be used before assignment" state across scopes.
7. **C backend cleanliness**: `CFunctionEmitter` must suppress label/unused warnings to compile under `-Wall -Wextra -Werror`. Do not remove suppression logic.
8. **JVM bytecode encoding**: only `JvmCodeBuilder` knows binary encoding. Do not emit raw bytes in `JvmInstructionEmitter` or elsewhere.
9. **Do not modify expected test outputs**: if a test fails, fix the compiler. Never change `.expected` files to hide regressions.
10. **Module import recursion**: `ModuleLoader.collectStatementImports` only recurses into `Block`/`If`/`While`/`For`/`Switch`. Extend if a new statement can contain `import`.

## 8. Repository layout and important commands

### Key directories
```
src/main/java/site/ilemon/
  lexer/          Token stream
  parser/         AST construction
  ast/            Node definitions
  semantic/       Type checking, scope, diagnostics
  optimizer/      Const-folding, dead code
  ir/             LemonIR lowerer, verifier, ARC optimizer
  arc/            Ownership analysis
  flow/           Null flow analysis
  backend/c/      C code generation
  backend/jvm/    JVM bytecode generation
  compiler/       Module loader, entry point
  diagnostic/     Error codes, rendering, JSON export
  type/           Type compatibility rules
  visitor/        Visitor interfaces
  util/           SourceSpan, DoublyLinkedList
  exception/      Compiler exception hierarchy

src/test/java/    JUnit 4 tests (no package) — 66 test classes, 615 test methods
examples/         .lemon source files (+ .c expected outputs)
  errors/         Negative test cases
  full_feature_matrix/
  large_benchmark/
  modules/
  module_struct_scope/
  pointer/
  pointer_full/
  pointer_showcase/
runtime/          C runtime library
  src/            Implementation (array.c, error.c, memory.c, string.c, etc.)
  include/        Headers
  tests/          Runtime smoke tests
tools/debug/      Debug utilities
docs/             Feature manual, ARC design, code review report
document/         Chinese-language project summaries
```

### Build and test commands
```bash
# Full test suite (615 tests)
mvn test

# Focused test classes
mvn -Dtest=SwitchTest test
mvn -Dtest=EnumTest test
mvn -Dtest=StructTest test
mvn -Dtest=PointerTest test
mvn -Dtest=ReturnPathAnalysisTest test
mvn -Dtest=LargeBenchmarkTest test
mvn -Dtest=FullFeatureMatrixTest test
mvn -Dtest=CBackendTest test
mvn -Dtest=JvmBackendTest test
mvn -Dtest=MultiBackendTest test
mvn -Dtest=AllExamplesJvmTest test

# Build assembly jar (for CLI usage)
mvn package
```

### Running examples
```bash
# Via jar
java -jar target/LemonC-0.1-beta-jar-with-dependencies.jar examples/HelloWorld.lemon --backend=jvm
java -jar target/LemonC-0.1-beta-jar-with-dependencies.jar examples/HelloWorld.lemon --backend=c

# Via Maven exec plugin
mvn exec:java -Dexec.mainClass=site.ilemon.compiler.LemonC -Dexec.args="examples/HelloWorld.lemon --backend=jvm"
```
