#include "lemon_memory.h"
#include "lemon_error.h"
#include <stdlib.h>

void *lemon_alloc(size_t size) { void *p = malloc(size); if (p == NULL && size != 0) lemon_panic("allocation failed"); return p; }
void *lemon_calloc(size_t count, size_t size) { void *p = calloc(count, size); if (p == NULL && count != 0 && size != 0) lemon_panic("allocation failed"); return p; }
void *lemon_realloc(void *ptr, size_t size) { void *p = realloc(ptr, size); if (p == NULL && size != 0) lemon_panic("reallocation failed"); return p; }
void lemon_free(void *ptr) { free(ptr); }
