#include "lemon_runtime.h"

#include <stdio.h>
#include <stdlib.h>

void *lemon_alloc(size_t size) {
    void *memory = calloc(1, size);
    if (memory == NULL && size != 0) lemon_panic("allocation failed");
    return memory;
}

void lemon_dealloc(void *ptr) {
    free(ptr);
}

void lemon_bounds_check(const void *array, size_t length, size_t index) {
    if (array == NULL || index >= length) lemon_panic("array index out of bounds");
}

void lemon_panic(const char *message) {
    fprintf(stderr, "Lemon runtime error: %s\n", message == NULL ? "unknown error" : message);
    abort();
}
