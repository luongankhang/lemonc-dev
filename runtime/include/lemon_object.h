#ifndef LEMON_OBJECT_H
#define LEMON_OBJECT_H

#include <stddef.h>
#include <stdint.h>

typedef struct lemon_type_info lemon_type_info;
typedef struct lemon_object lemon_object;
typedef void (*lemon_destructor)(lemon_object *object);

struct lemon_type_info {
    const char *name;
    size_t object_size;
    size_t alignment;
    lemon_destructor destructor;
    uint32_t flags;
};

struct lemon_object {
    const lemon_type_info *type;
    size_t refcount;
    uint32_t flags;
};

void lemon_object_init(lemon_object *object, const lemon_type_info *type);
void lemon_retain(lemon_object *object);
void lemon_release(lemon_object *object);
size_t lemon_retain_count(const lemon_object *object);
void lemon_destroy(lemon_object *object);

#endif
