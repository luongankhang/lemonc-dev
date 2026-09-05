#ifndef LEMON_ERROR_H
#define LEMON_ERROR_H

#include <stddef.h>

void lemon_panic(const char *message);
void lemon_panic_size_overflow(void);
void lemon_panic_bounds(const void *array, size_t length, size_t index);
void lemon_panic_divzero(const char *message);
void lemon_require_ptr(const void *ptr);

#endif
