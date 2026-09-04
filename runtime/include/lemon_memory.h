#ifndef LEMON_MEMORY_H
#define LEMON_MEMORY_H

#include <stddef.h>

void *lemon_alloc(size_t size);
void *lemon_calloc(size_t count, size_t size);
void *lemon_realloc(void *ptr, size_t size);
void lemon_free(void *ptr);

#endif
