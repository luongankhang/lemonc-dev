#include "lemon_error.h"
#include <stdio.h>
#include <stdlib.h>

void lemon_panic(const char *message) { fprintf(stderr, "Lemon runtime error: %s\n", message == NULL ? "unknown error" : message); abort(); }
void lemon_panic_bounds(const void *array, size_t length, size_t index) { (void)array; (void)length; (void)index; lemon_panic("array index out of bounds"); }
void lemon_panic_divzero(const char *message) { lemon_panic(message == NULL ? "division by zero" : message); }
