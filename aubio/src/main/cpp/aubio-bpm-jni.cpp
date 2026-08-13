#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>

// Aubio headers
#include "aubio.h"
#include "fvec.h"

// Define log tags
#define LOG_TAG "aubio-bpm-jni"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Helper macro to get JNI class and method names
#define JNI_CLASS "com/bpmapp/audio/audio/AubioBpmDetector"

// Forward declarations
extern "C" JNIEXPORT jfloat JNICALL
Java_com_bpmapp_audio_audio_AubioBpmDetector_nativeDetectBpm(
    JNIEnv* env, jobject thiz, jstring audioPath, jint sampleRate, jint hopSize, jint bufferSize);

extern "C" JNIEXPORT jfloat JNICALL
Java_com_bpmapp_audio_audio_AubioBpmDetector_nativeDetectBpmFromSamples(
    JNIEnv* env, jobject thiz, jfloatArray samples, jint numSamples, jint sampleRate, jint hopSize, jint bufferSize);

extern "C" JNIEXPORT void JNICALL
Java_com_bpmapp_audio_audio_AubioBpmDetector_nativeCleanup(
    JNIEnv* env, jobject thiz);

// Growable array of beat times (in seconds) collected while processing a stream.
struct beat_list {
  double *data;
  size_t len;
  size_t cap;
};

static int beats_init(struct beat_list *bl, size_t hint) {
  if (hint < 64) hint = 64;
  bl->data = (double *)malloc(hint * sizeof(double));
  if (!bl->data) return -1;
  bl->len = 0;
  bl->cap = hint;
  return 0;
}

static int beats_append(struct beat_list *bl, double t) {
  if (bl->len == bl->cap) {
    size_t ncap = bl->cap * 2;
    double *nd = (double *)realloc(bl->data, ncap * sizeof(double));
    if (!nd) return -1;
    bl->data = nd;
    bl->cap = ncap;
  }
  bl->data[bl->len++] = t;
  return 0;
}

static void beats_free(struct beat_list *bl) {
  free(bl->data);
  bl->data = NULL;
  bl->len = 0;
  bl->cap = 0;
}

// Estimates BPM from the median inter-beat interval. This mirrors the
// reference Python implementation (aubio.tempo + median of intervals) and
// is far more robust than reading aubio_tempo_get_bpm(), which only returns
// the last instantaneous (and 120 BPM-biased) running estimate.
static float estimate_bpm_from_beats(const struct beat_list *bl) {
  if (bl->len < 2) return 0.0f;
  size_t n = bl->len - 1;
  double *intervals = (double *)malloc(n * sizeof(double));
  if (!intervals) return 0.0f;
  size_t m = 0;
  for (size_t i = 0; i < n; i++) {
    double d = bl->data[i + 1] - bl->data[i];
    if (d > 0.05) intervals[m++] = d;
  }
  if (m == 0) {
    free(intervals);
    return 0.0f;
  }
  // insertion sort (m is small, typically a few hundred entries)
  for (size_t i = 1; i < m; i++) {
    double key = intervals[i];
    size_t j = i;
    while (j > 0 && intervals[j - 1] > key) {
      intervals[j] = intervals[j - 1];
      j--;
    }
    intervals[j] = key;
  }
  double median = (m % 2) ? intervals[m / 2]
                          : (intervals[m / 2 - 1] + intervals[m / 2]) / 2.0;
  free(intervals);
  if (!(median > 0.0)) return 0.0f;
  double bpm = 60.0 / median;
  if (bpm < 10.0 || bpm > 500.0) return 0.0f;
  return (float)bpm;
}

// Implementation of nativeDetectBpm
JNIEXPORT jfloat JNICALL
Java_com_bpmapp_audio_audio_AubioBpmDetector_nativeDetectBpm(
    JNIEnv* env, jobject thiz, jstring audioPath, jint sampleRate, jint hopSize, jint bufferSize) {
    
    const char* path = env->GetStringUTFChars(audioPath, NULL);
    if (path == NULL) {
        LOGE("Failed to get audio path string");
        return 0.0f;
    }
    
    // Initialize aubio tempo detector
    uint_t winSize = bufferSize;
    uint_t hop = hopSize;
    
    // Create source for audio file
    aubio_source_t* source = new_aubio_source(path, sampleRate, hop);
    if (!source) {
        LOGE("Failed to create aubio source for file: %s", path);
        env->ReleaseStringUTFChars(audioPath, path);
        return 0.0f;
    }
    
    // Use the source's actual sample rate; fall back to the requested one if unavailable
    uint_t actualRate = aubio_source_get_samplerate(source);
    if (actualRate == 0) actualRate = sampleRate;
    LOGD("nativeDetectBpm source samplerate: %u (requested %d)", actualRate, sampleRate);
    
    aubio_tempo_t* tempo = new_aubio_tempo("default", winSize, hop, actualRate);
    
    if (!tempo) {
        LOGE("Failed to create aubio tempo detector");
        del_aubio_source(source);
        env->ReleaseStringUTFChars(audioPath, path);
        return 0.0f;
    }
    
    // Create fvec for input and output
    fvec_t* inputVec = new_fvec(hop);
    fvec_t* outputVec = new_fvec(2);
    
    if (!inputVec || !outputVec) {
        LOGE("Failed to create fvec vectors");
        if (inputVec) del_fvec(inputVec);
        if (outputVec) del_fvec(outputVec);
        del_aubio_source(source);
        del_aubio_tempo(tempo);
        env->ReleaseStringUTFChars(audioPath, path);
        return 0.0f;
    }
    
    // Read audio and collect beat times
    uint_t read = 0;
    uint_t total_read = 0;
    struct beat_list beats;
    if (beats_init(&beats, 512) != 0) {
        LOGE("Failed to allocate beat list");
        del_fvec(inputVec);
        del_fvec(outputVec);
        del_aubio_source(source);
        del_aubio_tempo(tempo);
        env->ReleaseStringUTFChars(audioPath, path);
        return 0.0f;
    }
    
    do {
        aubio_source_do(source, inputVec, &read);
        // skip a partially filled final block to avoid re-processing stale data
        if (read < hop) break;
        aubio_tempo_do(tempo, inputVec, outputVec);
        
        // output->data[0] is non-zero when the current frame is a detected beat
        if (outputVec->data[0] != 0.0f) {
            beats_append(&beats, (double)total_read / (double)actualRate);
        }
        total_read += read;
        
    } while (read == hop);
    
    float bpm = estimate_bpm_from_beats(&beats);
    size_t nbeats = beats.len;
    
    // Cleanup
    beats_free(&beats);
    del_fvec(inputVec);
    del_fvec(outputVec);
    del_aubio_source(source);
    del_aubio_tempo(tempo);
    env->ReleaseStringUTFChars(audioPath, path);
    
    // Validate BPM
    if (bpm > 0 && bpm < 300) {
        LOGD("Detected BPM: %.2f (median of %d beats) from file: %s", bpm, (int)nbeats, path);
        return bpm;
    }
    
    LOGD("Invalid BPM detected: %.2f, returning 0", bpm);
    return 0.0f;
}

// Implementation of nativeDetectBpmFromSamples
JNIEXPORT jfloat JNICALL
Java_com_bpmapp_audio_audio_AubioBpmDetector_nativeDetectBpmFromSamples(
    JNIEnv* env, jobject thiz, jfloatArray samples, jint numSamples, jint sampleRate, jint hopSize, jint bufferSize) {
    
    if (numSamples <= 0) {
        LOGE("Invalid number of samples: %d", numSamples);
        return 0.0f;
    }
    
    // Get sample data from Java array
    jfloat* javaSamples = env->GetFloatArrayElements(samples, NULL);
    if (!javaSamples) {
        LOGE("Failed to get sample array elements");
        return 0.0f;
    }
    
    // Initialize aubio tempo detector
    uint_t winSize = bufferSize;
    uint_t hop = hopSize;
    aubio_tempo_t* tempo = new_aubio_tempo("default", winSize, hop, sampleRate);
    
    if (!tempo) {
        LOGE("Failed to create aubio tempo detector");
        env->ReleaseFloatArrayElements(samples, javaSamples, JNI_ABORT);
        return 0.0f;
    }
    
    // Create fvec for aubio
    fvec_t* inputVec = new_fvec(hop);
    fvec_t* outputVec = new_fvec(2);
    
    if (!inputVec || !outputVec) {
        LOGE("Failed to create fvec vectors");
        if (inputVec) del_fvec(inputVec);
        if (outputVec) del_fvec(outputVec);
        del_aubio_tempo(tempo);
        env->ReleaseFloatArrayElements(samples, javaSamples, JNI_ABORT);
        return 0.0f;
    }
    
    // Process samples in chunks and collect beat times
    struct beat_list beats;
    if (beats_init(&beats, 512) != 0) {
        LOGE("Failed to allocate beat list");
        del_fvec(inputVec);
        del_fvec(outputVec);
        del_aubio_tempo(tempo);
        env->ReleaseFloatArrayElements(samples, javaSamples, JNI_ABORT);
        return 0.0f;
    }
    
    uint_t processed = 0;
    
    while (processed + hop <= (uint_t)numSamples) {
        // Copy samples to fvec
        for (uint_t i = 0; i < hop; i++) {
            inputVec->data[i] = javaSamples[processed + i];
        }
        
        // Process chunk
        aubio_tempo_do(tempo, inputVec, outputVec);
        
        // output->data[0] is non-zero when the current frame is a detected beat
        if (outputVec->data[0] != 0.0f) {
            beats_append(&beats, (double)processed / (double)sampleRate);
        }
        
        processed += hop;
    }
    
    float bpm = estimate_bpm_from_beats(&beats);
    size_t nbeats = beats.len;
    
    // Cleanup
    beats_free(&beats);
    del_fvec(inputVec);
    del_fvec(outputVec);
    del_aubio_tempo(tempo);
    env->ReleaseFloatArrayElements(samples, javaSamples, JNI_ABORT);
    
    // Validate BPM
    if (bpm > 0 && bpm < 300) {
        LOGD("Detected BPM from samples: %.2f (median of %d beats)", bpm, (int)nbeats);
        return bpm;
    }
    
    LOGD("Invalid BPM detected from samples: %.2f, returning 0", bpm);
    return 0.0f;
}

// Implementation of nativeCleanup
JNIEXPORT void JNICALL
Java_com_bpmapp_audio_audio_AubioBpmDetector_nativeCleanup(
    JNIEnv* env, jobject thiz) {
    // Currently no persistent resources to clean up
    // If we maintain state in the future, clean it here
    LOGD("nativeCleanup called");
}
