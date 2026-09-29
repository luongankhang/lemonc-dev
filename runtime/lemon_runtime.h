#ifndef LEMON_RUNTIME_H
#define LEMON_RUNTIME_H

#include <stddef.h>
#include "include/lemon_runtime_config.h"
#include "include/lemon_memory.h"
#include "include/lemon_object.h"
#include "include/lemon_string.h"
#include "include/lemon_array.h"
#include "include/lemon_error.h"
#include "include/lemon_floatfmt.h"

LEMON_INLINE void lemon_dealloc(void *ptr) {
    lemon_free(ptr);
}

LEMON_INLINE void lemon_bounds_check(const void *array, size_t length, size_t index) {
    if (LEMON_UNLIKELY(array == NULL || index >= length)) {
        lemon_panic_bounds(array, length, index);
    }
}

#endif
