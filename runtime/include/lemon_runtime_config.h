#ifndef LEMON_RUNTIME_CONFIG_H
#define LEMON_RUNTIME_CONFIG_H

#include <stddef.h>
#include <stdint.h>
#include <stdbool.h>

#define LEMON_RUNTIME_ABI_VERSION 1
#define LEMON_RC_NON_ATOMIC 1

/* Branch prediction and inlining macros */
#if defined(__GNUC__) || defined(__clang__)
#define LEMON_LIKELY(x)       __builtin_expect(!!(x), 1)
#define LEMON_UNLIKELY(x)     __builtin_expect(!!(x), 0)
#define LEMON_INLINE          static inline __attribute__((always_inline))
#define LEMON_NOINLINE        __attribute__((noinline))
#else
#define LEMON_LIKELY(x)       (x)
#define LEMON_UNLIKELY(x)     (x)
#define LEMON_INLINE          static inline
#define LEMON_NOINLINE
#endif

/* Debug validation configuration:
 * Active if LEMON_DEBUG is set, or if not in NDEBUG and not LEMON_RELEASE.
 */
#ifndef LEMON_RUNTIME_DEBUG
#if defined(LEMON_DEBUG) || (!defined(NDEBUG) && !defined(LEMON_RELEASE))
#define LEMON_RUNTIME_DEBUG 1
#else
#define LEMON_RUNTIME_DEBUG 0
#endif
#endif

/* Magic canaries for memory safety validation */
#define LEMON_OBJECT_MAGIC_ALIVE    0x4C454D4FUL  /* 'LEMO' */
#define LEMON_OBJECT_MAGIC_FREED    0xDEADBEEFUL  /* 'DEAD' */
#define LEMON_OBJECT_REFCOUNT_FREED 0xDEADC0DEUL
#define LEMON_ALLOC_MAGIC_ALIVE     0x4C454D41UL  /* 'LEMA' */
#define LEMON_ALLOC_MAGIC_FREED     0xDEADBAADUL
#define LEMON_TRAILER_MAGIC         0x454E444DUL  /* 'ENDM' */
#define LEMON_REFCOUNT_MAX          (SIZE_MAX / 2)

#endif

