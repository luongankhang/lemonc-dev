#ifndef LEMON_MEMORY_H
#define LEMON_MEMORY_H

#include <stddef.h>
#include <stdint.h>
#include "lemon_runtime_config.h"

void *lemon_alloc(size_t size);
void *lemon_calloc(size_t count, size_t size);
void *lemon_realloc(void *ptr, size_t size);
void lemon_free(void *ptr);

/* Debug / Memory safety diagnostic introspection */
size_t lemon_runtime_active_allocations(void);
size_t lemon_runtime_total_allocated_bytes(void);
size_t lemon_runtime_peak_allocated_bytes(void);
int lemon_runtime_check_leaks(void);
void lemon_runtime_reset_stats(void);
void lemon_runtime_dump_stats(void);

#endif
