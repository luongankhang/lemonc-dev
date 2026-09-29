#include "lemon_memory.h"
#include "lemon_error.h"
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#if LEMON_RUNTIME_DEBUG

typedef struct lemon_alloc_header {
    uint32_t magic;          /* LEMON_ALLOC_MAGIC_ALIVE */
    uint32_t flags;
    size_t size;             /* Requested user payload size */
    struct lemon_alloc_header *next;
    struct lemon_alloc_header *prev;
} lemon_alloc_header;

static lemon_alloc_header *g_alloc_head = NULL;
static size_t g_active_allocations = 0;
static size_t g_total_allocated_bytes = 0;
static size_t g_peak_allocated_bytes = 0;

static void lemon_alloc_list_insert(lemon_alloc_header *header) {
    header->next = g_alloc_head;
    header->prev = NULL;
    if (g_alloc_head != NULL) {
        g_alloc_head->prev = header;
    }
    g_alloc_head = header;
    g_active_allocations++;
    g_total_allocated_bytes += header->size;
    if (g_total_allocated_bytes > g_peak_allocated_bytes) {
        g_peak_allocated_bytes = g_total_allocated_bytes;
    }
}

static void lemon_alloc_list_remove(lemon_alloc_header *header) {
    if (header->prev != NULL) {
        header->prev->next = header->next;
    } else {
        g_alloc_head = header->next;
    }
    if (header->next != NULL) {
        header->next->prev = header->prev;
    }
    header->next = NULL;
    header->prev = NULL;
    g_active_allocations--;
    g_total_allocated_bytes -= header->size;
}

void *lemon_alloc(size_t size) {
    if (size == 0) size = 1;
    if (size > (SIZE_MAX / 2) - sizeof(lemon_alloc_header) - sizeof(uint32_t)) {
        lemon_panic_size_overflow();
    }
    size_t total = sizeof(lemon_alloc_header) + size + sizeof(uint32_t);
    lemon_alloc_header *h = (lemon_alloc_header *)malloc(total);
    if (LEMON_UNLIKELY(h == NULL)) lemon_panic("allocation failed");
    h->magic = (uint32_t)LEMON_ALLOC_MAGIC_ALIVE;
    h->flags = 0;
    h->size = size;
    lemon_alloc_list_insert(h);
    void *user_ptr = (void *)(h + 1);
    uint32_t *trailer = (uint32_t *)((char *)user_ptr + size);
    *trailer = (uint32_t)LEMON_TRAILER_MAGIC;
    return user_ptr;
}

void *lemon_calloc(size_t count, size_t size) {
    if (count != 0 && size > (SIZE_MAX / 2) / count) lemon_panic_size_overflow();
    size_t total_bytes = count * size;
    void *p = lemon_alloc(total_bytes);
    memset(p, 0, total_bytes == 0 ? 1 : total_bytes);
    return p;
}

void *lemon_realloc(void *ptr, size_t size) {
    if (ptr == NULL) return lemon_alloc(size);
    if (size == 0) {
        lemon_free(ptr);
        return NULL;
    }
    lemon_alloc_header *h = ((lemon_alloc_header *)ptr) - 1;
    if (LEMON_UNLIKELY(h->magic == (uint32_t)LEMON_ALLOC_MAGIC_FREED)) {
        lemon_panic("reallocation of already freed memory");
    }
    if (LEMON_UNLIKELY(h->magic != (uint32_t)LEMON_ALLOC_MAGIC_ALIVE)) {
        lemon_panic("invalid reallocation: pointer was not allocated by Lemon runtime");
    }
    uint32_t *trailer = (uint32_t *)((char *)ptr + h->size);
    if (LEMON_UNLIKELY(*trailer != (uint32_t)LEMON_TRAILER_MAGIC)) {
        lemon_panic("heap buffer overflow detected during realloc");
    }
    void *new_p = lemon_alloc(size);
    size_t copy_size = h->size < size ? h->size : size;
    memcpy(new_p, ptr, copy_size);
    lemon_free(ptr);
    return new_p;
}

void lemon_free(void *ptr) {
    if (ptr == NULL) return;
    lemon_alloc_header *h = ((lemon_alloc_header *)ptr) - 1;
    if (LEMON_UNLIKELY(h->magic == (uint32_t)LEMON_ALLOC_MAGIC_FREED)) {
        lemon_panic("double free: pointer was already freed");
    }
    if (LEMON_UNLIKELY(h->magic != (uint32_t)LEMON_ALLOC_MAGIC_ALIVE)) {
        lemon_panic("invalid free: pointer was not allocated by Lemon runtime");
    }
    uint32_t *trailer = (uint32_t *)((char *)ptr + h->size);
    if (LEMON_UNLIKELY(*trailer != (uint32_t)LEMON_TRAILER_MAGIC)) {
        lemon_panic("heap buffer overflow detected: memory past allocation bounds was overwritten");
    }
    lemon_alloc_list_remove(h);
    // Poison user payload with 0xDD pattern to expose use-after-free
    memset(ptr, 0xDD, h->size);
    h->magic = (uint32_t)LEMON_ALLOC_MAGIC_FREED;
    free(h);
}

size_t lemon_runtime_active_allocations(void) { return g_active_allocations; }
size_t lemon_runtime_total_allocated_bytes(void) { return g_total_allocated_bytes; }
size_t lemon_runtime_peak_allocated_bytes(void) { return g_peak_allocated_bytes; }

void lemon_runtime_reset_stats(void) {
    g_active_allocations = 0;
    g_total_allocated_bytes = 0;
    g_peak_allocated_bytes = 0;
    g_alloc_head = NULL;
}

int lemon_runtime_check_leaks(void) {
    if (g_active_allocations == 0) return 0;
    fprintf(stderr, "Lemon runtime leak detected: %zu active allocation(s) totaling %zu bytes\n",
            g_active_allocations, g_total_allocated_bytes);
    lemon_alloc_header *curr = g_alloc_head;
    size_t count = 0;
    while (curr != NULL && count < 10) {
        fprintf(stderr, "  leaked block %zu: size %zu bytes at %p\n", count + 1, curr->size, (void *)(curr + 1));
        curr = curr->next;
        count++;
    }
    if (count < g_active_allocations) {
        fprintf(stderr, "  ... and %zu more block(s)\n", g_active_allocations - count);
    }
    return 1;
}

void lemon_runtime_dump_stats(void) {
    fprintf(stderr, "Lemon runtime memory stats: active=%zu, bytes=%zu, peak=%zu\n",
            g_active_allocations, g_total_allocated_bytes, g_peak_allocated_bytes);
}

#else

void *lemon_alloc(size_t size) {
    if (size == 0) size = 1;
    void *p = malloc(size);
    if (LEMON_UNLIKELY(p == NULL)) lemon_panic("allocation failed");
    return p;
}

void *lemon_calloc(size_t count, size_t size) {
    if (LEMON_UNLIKELY(count != 0 && size > (SIZE_MAX / 2) / count)) lemon_panic_size_overflow();
    if (count == 0 || size == 0) {
        count = 1;
        size = 1;
    }
    void *p = calloc(count, size);
    if (LEMON_UNLIKELY(p == NULL)) lemon_panic("allocation failed");
    return p;
}

void *lemon_realloc(void *ptr, size_t size) {
    if (size == 0) {
        free(ptr);
        return NULL;
    }
    void *p = realloc(ptr, size);
    if (LEMON_UNLIKELY(p == NULL)) lemon_panic("reallocation failed");
    return p;
}

void lemon_free(void *ptr) {
    free(ptr);
}

size_t lemon_runtime_active_allocations(void) { return 0; }
size_t lemon_runtime_total_allocated_bytes(void) { return 0; }
size_t lemon_runtime_peak_allocated_bytes(void) { return 0; }
int lemon_runtime_check_leaks(void) { return 0; }
void lemon_runtime_reset_stats(void) {}
void lemon_runtime_dump_stats(void) {}

#endif
