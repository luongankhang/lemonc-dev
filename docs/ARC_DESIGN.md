# LemonC ARC Design

## Status

This document is a design specification only. It does not enable ARC, change
the JVM backend, or add retain/release bytecode. The current implementation
continues to use JVM garbage collection. An ownership pass and simulator may
be implemented only after this specification is reviewed.

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

Add `--dump-arc` only after Ownership IR exists. It should print source-linked
abstract operations, for example:

```text
%a0 = Alloc short[]                       line 4
Retain %a0 -> local values                line 4
BoundsCheck values, index                 line 5
Release local values                      line 9
```

The flag must not change generated JVM bytecode or runtime behavior. It should
be compatible with existing inspection flags and should make ownership state
visible for teaching and debugging.

## 7. Implementation Plan

1. Review and approve this document.
2. Add only the `site.ilemon.arc` data model and operation validation.
3. Add AST-to-Ownership-IR analysis after semantic validation.
4. Add path-aware reference simulator and negative tests.
5. Add `--dump-arc` and documentation links.
6. Add a future C lowering proposal; do not implement C retain/release yet.

Every step must preserve the existing JVM pipeline and pass `mvn clean test`.
