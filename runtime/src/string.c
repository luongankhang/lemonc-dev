#include "lemon_string.h"
#include "lemon_memory.h"
#include "lemon_error.h"
#include <limits.h>
#include <string.h>

static lemon_type_info string_type = { "string", sizeof(lemon_string), 0, lemon_string_destroy, 0 };
lemon_string *lemon_string_new(const char *text) {
    size_t length = text == NULL ? 0 : strlen(text);
    if (length > SIZE_MAX - 1) lemon_panic_size_overflow();
    lemon_string *s = lemon_calloc(1, sizeof(*s));
    lemon_object_init(&s->object, &string_type);
    s->length = length;
    s->capacity = length + 1;
    s->data = lemon_alloc(s->capacity);
    if (text != NULL) memcpy(s->data, text, length);
    s->data[length] = '\0';
    return s;
}
lemon_string *lemon_string_concat(const lemon_string *left, const lemon_string *right) { size_t l = left == NULL ? 0 : left->length, r = right == NULL ? 0 : right->length; if (l > SIZE_MAX - r) lemon_panic_size_overflow(); lemon_string *s = lemon_string_new(NULL); s->length = l + r; s->capacity = s->length + 1; s->data = lemon_realloc(s->data, s->capacity); if (left != NULL) memcpy(s->data, left->data, l); if (right != NULL) memcpy(s->data + l, right->data, r); s->data[s->length] = '\0'; return s; }
int lemon_string_compare(const lemon_string *left, const lemon_string *right) { const char *l = left == NULL ? "" : left->data, *r = right == NULL ? "" : right->data; return strcmp(l, r); }
void lemon_string_destroy(lemon_object *object) { lemon_string *s = (lemon_string *)object; lemon_free(s->data); lemon_free(s); }
