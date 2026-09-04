#include "lemon_object.h"
#include "lemon_error.h"

void lemon_object_init(lemon_object *object, const lemon_type_info *type) { if (object == NULL) lemon_panic("null object"); object->type = type; object->refcount = 1; object->flags = 0; }
void lemon_retain(void *ptr) { if (ptr == NULL) lemon_panic("retain of null object"); lemon_object *object = (lemon_object *)ptr; if (object->refcount == 0) lemon_panic("retain of invalid object"); object->refcount++; }
void lemon_release(void *ptr) { if (ptr == NULL) return; lemon_object *object = (lemon_object *)ptr; if (object->refcount == 0) lemon_panic("double release or invalid object"); if (--object->refcount == 0) lemon_destroy(object); }
size_t lemon_retain_count(const lemon_object *object) { return object == NULL ? 0 : object->refcount; }
void lemon_destroy(lemon_object *object) { if (object == NULL || object->refcount != 0) lemon_panic("destroy of live object"); if (object->type != NULL && object->type->destructor != NULL) object->type->destructor(object); }
