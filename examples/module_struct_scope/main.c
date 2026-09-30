#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>
#include <stdio.h>
#include <math.h>
#include <limits.h>
#include "lemon_runtime.h"

typedef struct { unsigned char _opaque; } lemon_opaque_t;

typedef struct LemonC_User {
    int32_t id;
    int32_t age;
    int32_t score;
} LemonC_User;
typedef struct LemonC_UserSecret {
    int32_t pinCode;
} LemonC_UserSecret;
typedef struct LemonC_Product {
    int32_t sku;
    int32_t price;
    int32_t stock;
} LemonC_Product;
typedef struct LemonC_ProductInternalTag {
    int32_t warehouseCode;
} LemonC_ProductInternalTag;

void printUser(LemonC_User u);
void printProduct(LemonC_Product p);
int32_t main();
LemonC_User user_createUser(int32_t id, int32_t age, int32_t score);
void user_updateUserScore(LemonC_User* u, int32_t delta);
int32_t user_getUserScore(LemonC_User u);
LemonC_Product prod_createProduct(int32_t sku, int32_t price, int32_t stock);
void prod_applyDiscount(LemonC_Product* p, int32_t discount);
int32_t prod_getInventoryValue(LemonC_Product p);

void printUser(LemonC_User u) {
    int32_t _t1 = 0;
    int32_t _t2 = 0;
    int32_t _t3 = 0;
    _t1 = u.id;
    _t2 = u.age;
    _t3 = u.score;
    printf("User[id=");
    printf("%d", _t1);
    printf(", age=");
    printf("%d", _t2);
    printf(", score=");
    printf("%d", _t3);
    printf("]\n");
    return;
}

void printProduct(LemonC_Product p) {
    int32_t _t4 = 0;
    int32_t _t5 = 0;
    int32_t _t6 = 0;
    _t4 = p.sku;
    _t5 = p.price;
    _t6 = p.stock;
    printf("Product[sku=");
    printf("%d", _t4);
    printf(", price=");
    printf("%d", _t5);
    printf(", stock=");
    printf("%d", _t6);
    printf("]\n");
    return;
}

int32_t main() {
    int32_t _t7 = 0;
    int32_t _t8 = 0;
    int32_t _t9 = 0;
    LemonC_User _t10;
    LemonC_User u1;
    int32_t _t11 = 0;
    int32_t _t12 = 0;
    int32_t _t13 = 0;
    LemonC_Product _t14;
    LemonC_Product p1;
    int32_t _t15 = 0;
    int32_t _t16 = 0;
    int32_t _t17 = 0;
    int32_t _t18 = 0;
    int32_t _t19 = 0;
    int32_t _t20 = 0;
    LemonC_User u2;
    int32_t _t21 = 0;
    int32_t _t22 = 0;
    int32_t _t23 = 0;
    LemonC_Product p2;
    int32_t _t24 = 0;
    int32_t _t25 = 0;
    int32_t _t26 = 0;
    int32_t _t27 = 0;
    int32_t _t28 = 0;
    int32_t _t29 = 0;
    int32_t _t30 = 0;
    LemonC_User* _t31 = 0;
    LemonC_User* uPtr = 0;
    int32_t _t32 = 0;
    int32_t _t33 = 0;
    LemonC_Product* _t34 = 0;
    LemonC_Product* pPtr = 0;
    int32_t _t35 = 0;
    int32_t _t36 = 0;
    int32_t _t37 = 0;
    int32_t _t38 = 0;
    int32_t _t39 = 0;
    int32_t _t40 = 0;
    int32_t _t41 = 0;
    int32_t finalUserScore = 0;
    int32_t _t42 = 0;
    int32_t totalInventory = 0;
    lemon_array* report = 0;
    int32_t _t43 = 0;
    int32_t _t44 = 0;
    int32_t _t45 = 0;
    int32_t _t46 = 0;
    int32_t _t47 = 0;
    int32_t _t48 = 0;
    int32_t _t49 = 0;
    int32_t _t50 = 0;
    int32_t _t51 = 0;
    int32_t _t52 = 0;
    int32_t _t53 = 0;
    int32_t _t54 = 0;
    int32_t _t55 = 0;
    int32_t _t56 = 0;
    int32_t _t57 = 0;
    int32_t _t58 = 0;
    _t7 = 101;
    _t8 = 25;
    _t9 = 500;
    _t10 = user_createUser(_t7, _t8, _t9);
    u1 = _t10;
    _t11 = 9001;
    _t12 = 50;
    _t13 = 20;
    _t14 = prod_createProduct(_t11, _t12, _t13);
    p1 = _t14;
    _t15 = u1.id;
    _t16 = u1.age;
    _t17 = u1.score;
    printf("Created user: id=");
    printf("%d", _t15);
    printf(", age=");
    printf("%d", _t16);
    printf(", score=");
    printf("%d", _t17);
    printf("\n");
    _t18 = p1.sku;
    _t19 = p1.price;
    _t20 = p1.stock;
    printf("Created product: sku=");
    printf("%d", _t18);
    printf(", price=");
    printf("%d", _t19);
    printf(", stock=");
    printf("%d", _t20);
    printf("\n");
    u2 = u1;
    _t21 = 102;
    u2.id = _t21;
    _t22 = 26;
    u2.age = _t22;
    _t23 = 650;
    u2.score = _t23;
    p2 = p1;
    _t24 = 9002;
    p2.sku = _t24;
    _t25 = 80;
    p2.price = _t25;
    _t26 = 15;
    p2.stock = _t26;
    _t27 = u1.score;
    _t28 = u2.score;
    printf("After copy - u1 score: ");
    printf("%d", _t27);
    printf(", u2 score: ");
    printf("%d", _t28);
    printf("\n");
    _t29 = p1.price;
    _t30 = p2.price;
    printf("After copy - p1 price: ");
    printf("%d", _t29);
    printf(", p2 price: ");
    printf("%d", _t30);
    printf("\n");
    printUser(u2);
    printProduct(p2);
    _t31 = &(u1);
    uPtr = ((LemonC_User*)(_t31));
    _t32 = 150;
    user_updateUserScore(uPtr, _t32);
    _t33 = u1.score;
    printf("Updated u1 score via pointer: ");
    printf("%d", _t33);
    printf("\n");
    _t34 = &(p1);
    pPtr = ((LemonC_Product*)(_t34));
    _t35 = 10;
    prod_applyDiscount(pPtr, _t35);
    _t36 = p1.price;
    printf("Updated p1 price via pointer: ");
    printf("%d", _t36);
    printf("\n");
    _t37 = 30;
    uPtr->age = _t37;
    _t38 = 25;
    pPtr->stock = _t38;
    _t39 = uPtr->age;
    _t40 = pPtr->stock;
    printf("Direct arrow access - u1 age: ");
    printf("%d", _t39);
    printf(", p1 stock: ");
    printf("%d", _t40);
    printf("\n");
    _t41 = user_getUserScore(u1);
    finalUserScore = ((int32_t)(_t41));
    _t42 = prod_getInventoryValue(p1);
    totalInventory = ((int32_t)(_t42));
    printf("Calculations - user score: ");
    printf("%d", finalUserScore);
    printf(", inventory value: ");
    printf("%d", totalInventory);
    printf("\n");
    report = lemon_array_new((size_t)(4), sizeof(int32_t), NULL);
    _t43 = 0;
    _t44 = u1.id;
    lemon_bounds_check(report, report->length, (size_t)(_t43));
    *((int32_t*)lemon_array_at(report, (size_t)(_t43))) = _t44;
    _t45 = 1;
    _t46 = u1.score;
    lemon_bounds_check(report, report->length, (size_t)(_t45));
    *((int32_t*)lemon_array_at(report, (size_t)(_t45))) = _t46;
    _t47 = 2;
    _t48 = p1.sku;
    lemon_bounds_check(report, report->length, (size_t)(_t47));
    *((int32_t*)lemon_array_at(report, (size_t)(_t47))) = _t48;
    _t49 = 3;
    _t50 = p1.price;
    lemon_bounds_check(report, report->length, (size_t)(_t49));
    *((int32_t*)lemon_array_at(report, (size_t)(_t49))) = _t50;
    _t51 = 0;
    lemon_bounds_check(report, report->length, (size_t)(_t51));
    _t52 = *((int32_t*)lemon_array_at(report, (size_t)(_t51)));
    _t53 = 1;
    lemon_bounds_check(report, report->length, (size_t)(_t53));
    _t54 = *((int32_t*)lemon_array_at(report, (size_t)(_t53)));
    _t55 = 2;
    lemon_bounds_check(report, report->length, (size_t)(_t55));
    _t56 = *((int32_t*)lemon_array_at(report, (size_t)(_t55)));
    _t57 = 3;
    lemon_bounds_check(report, report->length, (size_t)(_t57));
    _t58 = *((int32_t*)lemon_array_at(report, (size_t)(_t57)));
    printf("Report summary: ");
    printf("%d", _t52);
    printf(", ");
    printf("%d", _t54);
    printf(", ");
    printf("%d", _t56);
    printf(", ");
    printf("%d", _t58);
    printf("\n");
    lemon_release(report);
    return 0;
}

LemonC_User user_createUser(int32_t id, int32_t age, int32_t score) {
    LemonC_User u;
    u = (LemonC_User){0};
    u.id = id;
    u.age = age;
    u.score = score;
    return u;
}

void user_updateUserScore(LemonC_User* u, int32_t delta) {
    int32_t _t59 = 0;
    int32_t _t60 = 0;
    _t59 = u->score;
    _t60 = _t59 + delta;
    (lemon_require_ptr(u), u)->score = _t60;
    return;
}

int32_t user_getUserScore(LemonC_User u) {
    int32_t _t61 = 0;
    _t61 = u.score;
    return _t61;
}

LemonC_Product prod_createProduct(int32_t sku, int32_t price, int32_t stock) {
    LemonC_Product p;
    p = (LemonC_Product){0};
    p.sku = sku;
    p.price = price;
    p.stock = stock;
    return p;
}

void prod_applyDiscount(LemonC_Product* p, int32_t discount) {
    int32_t _t62 = 0;
    int32_t _t63 = 0;
    _t62 = p->price;
    _t63 = _t62 - discount;
    (lemon_require_ptr(p), p)->price = _t63;
    return;
}

int32_t prod_getInventoryValue(LemonC_Product p) {
    int32_t _t64 = 0;
    int32_t _t65 = 0;
    int32_t _t66 = 0;
    _t64 = p.price;
    _t65 = p.stock;
    _t66 = _t64 * _t65;
    return _t66;
}

