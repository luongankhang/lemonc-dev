#include "lemon_memory.h"
#include "lemon_error.h"
#include <limits.h>
#include <stdlib.h>

void *lemon_alloc(size_t size) {
    if (size == 0) size = 1;
    void *p = malloc(size);
    if (p == NULL) lemon_panic("allocation failed");
    return p;
}

void *lemon_calloc(size_t count, size_t size) {
    if (count != 0 && size > SIZE_MAX / count) lemon_panic_size_overflow();
    if (count == 0 || size == 0) {
        count = 1;
        size = 1;
    }
    void *p = calloc(count, size);
    if (p == NULL) lemon_panic("allocation failed");
    return p;
}

void *lemon_realloc(void *ptr, size_t size) {
    if (size == 0) {
        free(ptr);
        return NULL;
    }
    void *p = realloc(ptr, size);
    if (p == NULL) lemon_panic("reallocation failed");
    return p;
}

void lemon_free(void *ptr) { free(ptr); }
