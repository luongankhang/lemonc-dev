# LemonC ARC Design

## Status

**Implemented and Active.** The ARC system features a production-quality
control-flow graph (CFG) ownership analyzer (`OwnershipAnalyzer`), a path-sensitive
abstract interpretation simulator (`RefcountSimulator`), standardized diagnostic
codes (`E8001`-`E8006`), and CLI options (`--arc`, `--arc-verify`, `--arc-analysis`,
`--arc-debug`, `--dump-arc`).
The JVM backend continues using standard JVM garbage collection, while ARC provides
compile-time safety, ownership verification, and the foundation for native C lowering.

## 1. Scope and Managed Values

LemonC is currently a single-class teaching language. It has no user-created
class instances, structs, fields, pointers, references, or explicit `new` and
`delete` expressions. The minimum useful ARC scope is therefore:

| Value category | Current status | ARC status |
|---|---|---|
| `int`, `float`, `double`, `bool`, `byte`, `short`, `char`, `long` | Immediate scalar values | Never ARC-managed |
| `string` literal | JVM `String` reference used by output | Managed reference in ownership IR; JVM remains GC-owned |
| All array types | Heap-allocated JVM arrays | Managed reference in ownership IR |
| Struct/class instance | Not in current grammar or AST | Future managed aggregate |
| Pointer/reference | Not in current grammar or AST | Future explicit ownership category |

`string[]` is an array reference managed as an aggregate; its elements are
references and require element-store rules when the object model supports
owned string elements. The current language primarily uses string literals and
does not support general scalar string variables.

No grammar expansion is required for the minimum design. Existing array
allocation and string literal paths provide enough heap-shaped values to
validate an ownership model. Adding objects later should be a separate
language design, not an implicit extension of this pass.

## 2. Ownership Model

The initial model uses **copy-on-assign with retain-on-store**. This is simpler
than move semantics, matches ordinary teaching-language assignment, and makes
the ownership invariant local and inspectable.

### Strong references

- A local variable containing a managed value owns one strong reference while
  its lifetime is active.
- An owning parameter owns one strong reference for the duration of the callee
  invocation. The call boundary transfers no ownership implicitly; the callee
  receives a retained parameter view.
- A future field owns one strong reference while the containing object owns the
  field.
- A future managed array element owns one strong reference after an element
  store. Primitive array elements are values and never retain.
- A newly allocated value starts with one strong reference owned by the
  allocating expression's destination or temporary.

### Assignment, calls, and returns

For `destination = source` where both are managed references:

1. Retain `source`.
2. Release the old value in `destination`.
3. Store the new reference.

The retain-before-release order is required for self-assignment and aliasing
safety. A returned managed value transfers one owned reference to the caller.
The callee releases its local ownership on every exit path after preparing the
return value. The caller owns the returned reference and releases it when its
temporary or destination lifetime ends.

For a method parameter, the caller retains for the call only if the ABI model
requires an owning argument; the initial Ownership IR will make this explicit
with `CallEnter`/`CallExit` annotations rather than hiding it in `Call`.

## 3. Memory Operation Insertion

Memory operations are abstract annotations, not JVM instructions. They are
attached to ownership IR events after semantic analysis and before any target
lowering.

| Source construct | Managed-value operation | Primitive-value operation |
|---|---|---|
| Array creation `T a[n]` | `Alloc(array<T>)`; bind one owned reference to `a` | None for elements |
| String literal used as a value | `Alloc(string)` only if materialized as an owned runtime object; otherwise `BorrowLiteral` | None |
| Local declaration | Establish an uninitialized ownership slot; no retain | Establish scalar slot |
| `a = b` | `Retain(b)`, `Release(old(a))`, `Store(a,b)` | Typed scalar store |
| `a[i] = b` for managed element `b` | Bounds check; `Retain(b)`, `Release(old(a[i]))`, managed element store | Bounds check and primitive store |
| `a[i] = v` for primitive element | Bounds check and primitive store | Bounds check and primitive store |
| Read `a[i]` | Bounds check; borrowed read or retain when captured as an owned value | Primitive load |
| `a.length` | Borrowed metadata read; no retain of array | Scalar result |
| Method call with managed argument | Explicit call-argument retain according to parameter ownership; call boundary record | Ordinary argument lowering |
| Managed method return | Produce one owned result; release callee locals on exit | Ordinary return |
| `if/else` | Each branch releases values whose branch-local scope ends; merge ownership at join | Ordinary control flow |
| `while` | Loop-carried managed values require explicit ownership merge/phi; release iteration temporaries | Ordinary loop control flow |
| `for` | Same as `while`; release initializer/update temporaries at their scope exits | Ordinary loop control flow |
| `break` | Release all active loop-scope owned locals before branch to loop exit | Ordinary branch |
| `continue` | Release locals whose scope ends before the loop back-edge | Ordinary branch |
| Block `{ ... }` | Release block-local owned values at normal and non-local exits | Ordinary scope exit |
| `return e` | Retain/transfer result as specified, release active locals, then return | Ordinary return |
| Recursive call | Apply normal argument/return rules per invocation; no global special case | Ordinary call |
| Exception path | Not present in current grammar; future lowering must add cleanup edges | N/A |

The pass must model cleanup on all control-flow exits, not only the textual
last statement of a method. `break`, `continue`, branch joins, early return,
and future exception edges are ownership edges.

## 4. ARC Limitations

ARC does not break reference cycles. The current language has no reverse
references or object fields, so cycles cannot currently be expressed. When
objects and pointers are added, LemonC should introduce an explicit `weak`
reference category and reject unsupported strong cycles in semantic analysis.
This is preferable to silently leaking or pretending ARC is a tracing GC.

Reference counting adds retain/release overhead. That cost is accepted for the
educational and correctness goals of the first implementation. Later passes
may remove redundant operations when proven safe.

## 5. Compiler Representation

The proposed package is `site.ilemon.arc` and remains separate from
`TranslatorVisitor` and `ByteCodeGenerator`.

### Ownership IR

The ownership layer should contain:

- `OwnershipModule`, `OwnershipFunction`, and `OwnershipBlock`.
- Source-linked events for allocation, retain, release, load, store, call,
  return, branch, and scope exit.
- A managed-value identity for each allocation, independent of variable names.
- Ownership state (`owned`, `borrowed`, `moved`, `released`) for analysis.
- Source location metadata for diagnostics and debug dumps.
- Explicit branch edges and merge points for future phi/SSA support.

The abstract operations are:

```text
Alloc(type)
Retain(value)
Release(value)
Load(address)
Store(address, value)
CallEnter(function, arguments)
CallExit(function, result)
ScopeExit(scope)
BoundsCheck(array, index)
Return(value)
```

The future C backend can lower these to `lemon_alloc`, `lemon_retain`,
`lemon_release`, `lemon_load`, and `lemon_store`. The JVM backend must not emit
these calls; it continues using JVM GC and may only display annotations with a
debug flag.

### Simulator contract

`RefcountSimulator` will interpret Ownership IR without generating machine
code. For every abstract allocation it must detect:

- Release before the first ownership exists.
- Double release.
- Use after release.
- Missing release on every modeled exit path.
- Invalid retain/release of primitive values.
- Inconsistent ownership state at a CFG merge.

The simulator should report allocation identity and source span, not merely a
global operation count. `total retain == total release` is necessary but not
sufficient; path-sensitive state and use-after-release checks are required.

## 6. CLI and Debugging
 
LemonC provides dedicated ARC command-line options:
 
- `--arc`: Enables ARC analysis and runs automatic ownership verification.
- `--arc-verify`: Runs the path-sensitive `RefcountSimulator` on the ownership CFG and reports diagnostics (`E8001`-`E8006`), exiting with code 1 on failure.
- `--arc-analysis` (or `--dump-arc`): Dumps the linearized sequence of memory operations and basic block events.
- `--arc-debug`: Prints the full CFG structure including blocks, terminator jump types, successors, and per-block operations.
 
Example output with `--arc-analysis`:
 
```text
== ARC ==
ALLOC values:@short[] (line 4)
RETAIN values (line 4)
BOUNDS_CHECK values (line 5)
RELEASE values (line 9)
SCOPE_EXIT main (line 9)
```
 
The flags do not alter the generated JVM bytecode, ensuring 100% backward compatibility.
 
## 7. Diagnostics Reference
 
| Code | Error Name | Description |
|---|---|---|
| `E8001` | `ARC_DOUBLE_RELEASE` | Releasing a managed reference whose refcount is already 0 or already released |
| `E8002` | `ARC_USE_AFTER_RELEASE` | Accessing, retaining, or indexing a reference after it has been released |
| `E8003` | `ARC_MISSING_RELEASE` | Leaking a managed heap reference at function exit or scope termination |
| `E8004` | `ARC_OWNERSHIP_VIOLATION` | Refcount or ownership status mismatch across converging CFG branches |
| `E8005` | `ARC_INVALID_MOVE_COPY` | Illegal move from an uninitialized or already moved value |
| `E8006` | `ARC_LIFETIME_VIOLATION` | Reference escapes local scope without valid ownership transfer |
