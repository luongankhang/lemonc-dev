#ifndef LEMON_RUNTIME_H
#define LEMON_RUNTIME_H

#include <stddef.h>

void *lemon_alloc(size_t size);
void lemon_dealloc(void *ptr);
void lemon_bounds_check(const void *array, size_t length, size_t index);
void lemon_panic(const char *message);

#endif
