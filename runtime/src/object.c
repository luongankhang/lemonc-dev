#include "lemon_object.h"
#include "lemon_error.h"
#include <stdio.h>

void lemon_object_init(lemon_object *object, const lemon_type_info *type) {
    if (LEMON_UNLIKELY(object == NULL)) {
        lemon_panic("null object initialization");
    }
    object->magic = (uint32_t)LEMON_OBJECT_MAGIC_ALIVE;
    object->flags = 0;
    object->type = type;
    object->refcount = 1;
}

void lemon_object_validate_alive(const lemon_object *object, const char *op) {
    if (LEMON_UNLIKELY(object == NULL)) {
        lemon_panic("null object pointer");
    }
    if (LEMON_UNLIKELY(object->magic == (uint32_t)LEMON_OBJECT_MAGIC_FREED ||
                       object->magic == 0xDDDDDDDDUL ||
                       object->refcount == (size_t)LEMON_OBJECT_REFCOUNT_FREED)) {
        char msg[128];
        snprintf(msg, sizeof(msg), "use-after-free or double release in %s: object has already been destroyed", op);
        lemon_panic(msg);
    }
    if (LEMON_UNLIKELY(object->magic != (uint32_t)LEMON_OBJECT_MAGIC_ALIVE)) {
        char msg[128];
        snprintf(msg, sizeof(msg), "invalid object in %s: bad magic 0x%08x (dangling pointer or corrupted memory)",
                 op, object->magic);
        lemon_panic(msg);
    }
    if (LEMON_UNLIKELY(object->refcount == 0)) {
        char msg[128];
        snprintf(msg, sizeof(msg), "invalid object in %s: refcount is 0", op);
        lemon_panic(msg);
    }
}

void lemon_retain(void *ptr) {
    if (LEMON_UNLIKELY(ptr == NULL)) return;
    lemon_object *object = (lemon_object *)ptr;
#if LEMON_RUNTIME_DEBUG
    lemon_object_validate_alive(object, "lemon_retain");
#endif
    if (LEMON_UNLIKELY(object->refcount >= (size_t)LEMON_REFCOUNT_MAX)) {
        lemon_panic("reference count overflow");
    }
    object->refcount++;
}

void lemon_release(void *ptr) {
    if (LEMON_UNLIKELY(ptr == NULL)) return;
    lemon_object *object = (lemon_object *)ptr;
#if LEMON_RUNTIME_DEBUG
    lemon_object_validate_alive(object, "lemon_release");
#endif
    if (LEMON_UNLIKELY(object->refcount == 0)) {
        lemon_panic("double release or use-after-free: refcount is already 0");
    }
    if (--object->refcount == 0) {
        lemon_destroy(object);
    }
}

size_t lemon_retain_count(const lemon_object *object) {
    if (object == NULL) return 0;
#if LEMON_RUNTIME_DEBUG
    if (object->magic != (uint32_t)LEMON_OBJECT_MAGIC_ALIVE) return 0;
#endif
    return object->refcount;
}

void lemon_destroy(lemon_object *object) {
    if (LEMON_UNLIKELY(object == NULL)) {
        lemon_panic("destroy of null object");
    }
    if (LEMON_UNLIKELY(object->refcount != 0)) {
        lemon_panic("destroy of live object with non-zero refcount");
    }
    const lemon_type_info *type = object->type;
    // Poison the object metadata to detect subsequent use-after-free or double-free
    object->magic = (uint32_t)LEMON_OBJECT_MAGIC_FREED;
    object->refcount = (size_t)LEMON_OBJECT_REFCOUNT_FREED;
    if (type != NULL && type->destructor != NULL) {
        type->destructor(object);
    }
}

