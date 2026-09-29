#include "lemon_error.h"
#include <stdio.h>
#include <stdlib.h>

void lemon_panic(const char *message) {
    fprintf(stderr, "Lemon runtime error: %s\n", message == NULL ? "unknown error" : message);
    abort();
}

void lemon_panic_size_overflow(void) {
    lemon_panic("size overflow");
}

void lemon_panic_bounds(const void *array, size_t length, size_t index) {
    (void)array;
    char msg[128];
    snprintf(msg, sizeof(msg), "array index out of bounds: index %zu, length %zu", index, length);
    lemon_panic(msg);
}

void lemon_panic_divzero(const char *message) {
    lemon_panic(message == NULL ? "division by zero" : message);
}

void lemon_panic_int_overflow(const char *op) {
    char msg[128];
    snprintf(msg, sizeof(msg), "integer overflow in %s", op == NULL ? "arithmetic operation" : op);
    lemon_panic(msg);
}

