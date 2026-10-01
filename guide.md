# LemonC Compiler Development Guide

Verified against source on 2026-10-01. Companion to `AGENTS.md` (architecture summary + coding rules). This guide walks a language feature through every pipeline stage with concrete code references.

## 1. Pipeline overview

```
source.lemon
   │
   ▼
Lexer (lexer/Lexer.java)            → tokens (TokenKind)
   ▼
Parser (parser/Parser.java)         → Ast.Program (ast/Ast.java)
   ▼
SemanticVisitor (semantic/)         → typed AST + diagnostics (E2xxx/E3xxx)
   ▼
AstOptimizer (optimizer/)           → folded AST
   ▼
AstToIrLowerer (ir/)                → IrModule (LemonIR: BasicBlock + IrInstruction)
   ▼
OwnershipAnalyzer (arc/)            → ARC annotations/verdicts (RETAIN/RELEASE emitted during lowering)
   ▼
┌──────────────────┬────────────────────────┐
│ C backend        │ JVM backend            │
│ backend/c/       │ backend/jvm/           │
│ emits C + goto   │ emits JVM bytecode     │
└──────────────────┴────────────────────────┘
```

One shared IR; backends never see a second IR. `PHI` must be lowered before backends.

## 2. How a feature flows through the pipeline

### 2.1 Lexer

Add keywords in `Lexer.java` static `KEYWORDS` map (`"while" → TokenKind.While` pattern). New punctuation → new `TokenKind` entry. Lexer errors use `lexicalError(...)` / `LexException`.

### 2.2 Parser

`parseStmt()` is a long `if/else if` chain on `look.kind`. A new statement needs:

1. A branch in `parseStmt()` — `match()` keywords/delimiters, build the AST node, set `lineNum`/`span` via `tokenSpan(token)`.
2. A line in `isStatementStart()` so statement-block parsing picks it up.
3. Recovery safety: parser errors call `error(...)` and the loop in `parseStmts()` catches `ParseException` and calls `synchronizeToStatementBoundary()`.

Nested statements: `While`/`For` recurse with `parseStmt()`; block-scoped bodies use `parseStmts()` inside `{ }`.

### 2.3 AST

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

### 2.4 Semantic

Implement `visit(MyStmt)` in `SemanticVisitor` (and any other `ISemanticVisitor` implementor — search `visit(Ast.Stmt.`). Conventions:

- **Scoping**: for a new block-like construct, mirror `visit(Ast.Stmt.For)`: `mTable.enterScope()` before body, snapshot `currMethodLocalVar` (`HashSet<String> before = new HashSet<>(...)`), restore after, `mTable.exitScope()`.
- **Type checks**: `this.visit(expr)` sets `this.currType`; then `typeError(DiagnosticCodes.TYPE_*, expected, actual, expressionName(...), lineNum, span, label, suggestion)`.
- **Loop depth**: `loopDepth++/--` around bodies so `visit(Ast.Stmt.Break)` (requires `loopDepth > 0`) works.
- **Flow**: `statementTerminates(stmt)` returns true for `Return`/`Break`/`Continue`; extend for new control flow if it affects "may be used before assignment" or return-path analysis (`ReturnPathAnalysisTest`).
- **Enums**: `enumTable` (name → `Ast.EnumDecl`), `enumMemberTable` (both `"Name.MEMBER"` and bare `"MEMBER"` keys), `resolveEnum(name)` with module alias support. Enum member expressions type as `Ast.Type.Enum`.

### 2.5 AstOptimizer

Add a recursive case in `optimizeStmt()`:

```java
if (stmt instanceof Ast.Stmt.MyStmt myStmt) {
    return new Ast.Stmt.MyStmt(/* optimized children */, myStmt.getLineNum());
}
```

**Caution**: unknown `Stmt` kinds fall through to `return stmt` (unchanged) — verify coverage by reading the chain, not by trusting.

### 2.6 LemonIR lowering (AstToIrLowerer)

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

### 2.7 ARC

`arc/OwnershipAnalyzer.emitStatement` walks IR statements (`While`, `For`, `Break` handled). If the new construct can own/release memory (managed locals in its scope), mirror the `While` handling: build `OwnershipBlock` regions for cond/body/exit and release on break paths.

### 2.8 C backend

No change needed unless new opcodes were introduced. `CFunctionEmitter.emit` hoists every instruction result as a C local and emits block labels `safe(block.name()):;` (only for blocks that are branch targets) + instructions via `CInstructionEmitter.emit`. `BRANCH` → `goto label;`, `COND_BRANCH` → `if (cond) goto label;`. Enum types emit via `CTypeEmitter` (`LemonC_<Name>`). Clean under `-Wall -Wextra -Werror`.

### 2.9 JVM backend

No change needed unless new opcodes were introduced. `JvmMethodEmitter.emit` labels every block (`code.label(block.name())`) and emits instructions; `JvmCodeBuilder.toBytecode()` patches branch offsets. Enum values are inlined as int constants.

### 2.10 Tests

- Positive/negative unit tests (`src/test/java`, JUnit 4).
- Multi-backend end-to-end: compile the same `.lemon` through C and JVM, compare output (pattern: `MultiBackendTest`, `PointerMultiBackendTest`).
- Diagnostics: assert codes/messages via `DiagnosticEngine`/`DiagnosticTestSupport`.
- Examples: add to `examples/*.lemon`; `AllExamplesJvmTest` exercises them.

## 3. Worked example: `switch/case/default` (implemented 2026-10-01)

**Status: fully implemented and verified.** The walkthrough below describes what was built; use it as the reference pattern for future control-flow features.

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
| Parser | `parseStmt()` branch for `switch`: `match("switch")`, `match("(")`, `parseExpr()`, `match(")")`, `match("{")`, then loop parsing `case <const-expr>: <stmts>` / `default:` until `match("}")`. Case bodies are statement lists (no `{}` needed) — use `parseStmts()`-style collection. Add `TokenKind.Switch`/`Case`/`Default` to `isStatementStart()`. |
| AST | `Ast.Stmt.Switch { Expr.T subject; List<CaseClause> cases; }` + `CaseClause { Expr.T label (null = default); List<Stmt.T> body; int lineNum; }`. |
| Semantic | `visit(Ast.Stmt.Switch)`: type-check subject (int-family or enum); each case label must be a constant of a compatible type; reject duplicate case values (set of resolved ints) and more than one `default`; enter a scope per switch (mirror `For`); increment a break depth that `visit(Ast.Stmt.Break)` accepts (loop **or** switch); type-check each case body. |
| AstOptimizer | Recursive case: optimize subject + case labels + bodies. |
| LemonIR | Lower to shared opcodes: `switch_cond` block evaluating subject into a temp, `COND_BRANCH`/`CMP` chain per case (or `case_body_i` blocks), `BRANCH` to bodies, default block, `switch_exit` block. Push a break context whose break target is `switch_exit`; body scopes pushed per case so `break` releases case-locals. **No new IR opcode** — reuse `CMP`/`COND_BRANCH`/`BRANCH`. |
| ARC | `Break` already handled; case-local managed vars released via scope release in lowering. |
| C backend | No change (gotos + labels only). |
| JVM backend | No change (labels + GOTO only). |
| Tests | positive + negative, enum + int, nested switch, switch in loop, fallthrough, break, default, duplicate case/default, scope, C/JVM end-to-end. |
| Examples | `examples/switch_showcase.lemon`. |

## 4. Semantics decisions (binding)

- **Fallthrough**: no `break` at the end of a case falls through into the next case body (C semantics). IR: case body without `break` gets a trailing `BRANCH` to the next case body's label, not to `switch_exit`.
- **Duplicate case**: semantic error `E2003`-family (duplicate declaration) with a clear message naming the value.
- **Duplicate default**: semantic error, at most one `default` clause.
- **Case must be constant**: labels restricted to integer literals / enum members / named constants that resolve to integers.
- **break inside switch**: exits the innermost switch (or loop if inside a loop body inside a switch — innermost wins).
- **Case variable scope**: declarations inside a case body are scoped to that case (consistent with the language's block scoping); `break` releases them.

## 5. Verification loop

```
mvn test                         # full suite — no regression
mvn -Dtest=SwitchTest test       # focused switch suite
mvn -Dtest=EnumTest test         # focused enum suite
mvn -Dtest=ReturnPathAnalysisTest test  # return-path analysis
```

Do not modify expected outputs to hide failures; fix the compiler.
