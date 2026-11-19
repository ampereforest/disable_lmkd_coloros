#include <android/log.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LOG_TAG "MemFillTest"
#define MAX_CHUNKS 512

static void* g_chunks[MAX_CHUNKS];
static int g_chunk_count = 0;
static int g_total_mb = 0;
static uint64_t g_rng = 0x9e3779b97f4a7c15ULL;

static void read_meminfo_mb(int* avail_mb, int* free_mb, int* swap_free_mb) {
    *avail_mb = -1;
    *free_mb = -1;
    *swap_free_mb = -1;
    FILE* f = fopen("/proc/meminfo", "r");
    if (!f) return;
    char key[64];
    long val = 0;
    while (fscanf(f, "%63s %ld kB", key, &val) == 2) {
        if (strcmp(key, "MemAvailable:") == 0) *avail_mb = (int)(val / 1024);
        else if (strcmp(key, "MemFree:") == 0) *free_mb = (int)(val / 1024);
        else if (strcmp(key, "SwapFree:") == 0) *swap_free_mb = (int)(val / 1024);
    }
    fclose(f);
}

JNIEXPORT jintArray JNICALL
Java_com_dada_memfill_MainActivity_nativeAllocStep(JNIEnv* env, jclass cls, jint stepMb, jint stepIdx) {
    (void)cls;
    if (stepMb <= 0 || g_chunk_count >= MAX_CHUNKS) return NULL;
    size_t bytes = (size_t)stepMb * 1024ULL * 1024ULL;
    uint8_t* ptr = (uint8_t*)malloc(bytes);
    if (!ptr) {
        int a = -1, fr = -1, sw = -1;
        read_meminfo_mb(&a, &fr, &sw);
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG,
            "step=%d malloc(%dMB) failed total_mb=%d mem_avail_mb=%d mem_free_mb=%d swap_free_mb=%d",
            stepIdx, stepMb, g_total_mb, a, fr, sw);
        return NULL;
    }

    // Initialize via memset first, then overwrite every 4KB page with non-zero xorshift64 words to avoid ZRAM deduplication.
    memset(ptr, 0x5A, bytes);
    uint64_t x = g_rng ^ ((uint64_t)(stepIdx + 1) * 0xbf58476d1ce4e5b9ULL);
    uint64_t* p64 = (uint64_t*)ptr;
    size_t words = bytes / sizeof(uint64_t);
    for (size_t i = 0; i < words; i++) {
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        uint64_t v = x * 0x2545F4914F6CDD1DULL;
        p64[i] = v ? v : 1ULL;
    }
    g_rng = x;
    g_chunks[g_chunk_count++] = ptr;
    g_total_mb += stepMb;

    int avail = -1, fr = -1, sw = -1;
    read_meminfo_mb(&avail, &fr, &sw);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
        "step=%d alloc_mb=%d mem_avail_mb=%d mem_free_mb=%d swap_free_mb=%d",
        stepIdx, g_total_mb, avail, fr, sw);

    jint out[4] = { g_total_mb, avail, fr, sw };
    jintArray arr = (*env)->NewIntArray(env, 4);
    if (arr) (*env)->SetIntArrayRegion(env, arr, 0, 4, out);
    return arr;
}

JNIEXPORT void JNICALL
Java_com_dada_memfill_MainActivity_nativeFreeAll(JNIEnv* env, jclass cls) {
    (void)env;
    (void)cls;
    for (int i = 0; i < g_chunk_count; i++) {
        free(g_chunks[i]);
        g_chunks[i] = NULL;
    }
    g_chunk_count = 0;
    g_total_mb = 0;
}
