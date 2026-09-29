#include "lemon_runtime.h"
#include <stdio.h>
#include <time.h>
#include <stdint.h>

static double get_time_sec(void) {
    return (double)clock() / CLOCKS_PER_SEC;
}

int main(void) {
    printf("=== LemonC Runtime Benchmark ===\n");

    /* Benchmark 1: Array access & bounds check throughput */
    const size_t arr_len = 1000000;
    const int iters = 50;
    lemon_array *arr = lemon_array_new(arr_len, sizeof(int32_t), NULL);
    for (size_t i = 0; i < arr_len; i++) {
        *(int32_t *)lemon_array_at(arr, i) = (int32_t)i;
    }

    double t0 = get_time_sec();
    int64_t sum = 0;
    for (int it = 0; it < iters; it++) {
        for (size_t i = 0; i < arr_len; i++) {
            sum += *(int32_t *)lemon_array_at(arr, i);
        }
    }
    double t1 = get_time_sec();
    double total_ops = (double)arr_len * iters;
    double mops = (total_ops / (t1 - t0)) / 1e6;
    printf("[1] Array Access: %.3f sec for %.0f ops (%.2f Mops/s), sum=%lld\n",
           t1 - t0, total_ops, mops, (long long)sum);

    /* Benchmark 2: Retain / Release throughput */
    const int rc_iters = 10000000;
    t0 = get_time_sec();
    for (int i = 0; i < rc_iters; i++) {
        lemon_retain(arr);
        lemon_release(arr);
    }
    t1 = get_time_sec();
    mops = ((double)rc_iters * 2.0 / (t1 - t0)) / 1e6;
    printf("[2] ARC Retain/Release: %.3f sec for %d pairs (%.2f Mops/s), refcount=%zu\n",
           t1 - t0, rc_iters, mops, lemon_retain_count(&arr->object));

    /* Benchmark 3: String creation & concatenation throughput */
    const int str_iters = 50000;
    t0 = get_time_sec();
    for (int i = 0; i < str_iters; i++) {
        lemon_string *s1 = lemon_string_new("hello ");
        lemon_string *s2 = lemon_string_new("world");
        lemon_string *s3 = lemon_string_concat(s1, s2);
        lemon_release(s1);
        lemon_release(s2);
        lemon_release(s3);
    }
    t1 = get_time_sec();
    mops = ((double)str_iters / (t1 - t0)) / 1e3;
    printf("[3] String Alloc/Concat/Free: %.3f sec for %d iterations (%.2f Kops/s)\n",
           t1 - t0, str_iters, mops);

    lemon_release(arr);

    /* Verify zero leaks at end */
    int leaks = lemon_runtime_check_leaks();
    printf("Leak check status: %s (active allocations = %zu)\n",
           leaks == 0 ? "PASSED (0 leaks)" : "FAILED (leaks detected)",
           lemon_runtime_active_allocations());

    return leaks == 0 ? 0 : 1;
}
