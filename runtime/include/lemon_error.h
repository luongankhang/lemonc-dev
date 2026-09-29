#ifndef LEMON_ERROR_H
#define LEMON_ERROR_H

#include <stddef.h>
#include "lemon_runtime_config.h"

void lemon_panic(const char *message);
void lemon_panic_size_overflow(void);
void lemon_panic_bounds(const void *array, size_t length, size_t index);
void lemon_panic_divzero(const char *message);
void lemon_panic_int_overflow(const char *op);

LEMON_INLINE void lemon_require_ptr(const void *ptr) {
    if (LEMON_UNLIKELY(ptr == NULL)) {
        lemon_panic("null pointer dereference");
    }
}

#endif
