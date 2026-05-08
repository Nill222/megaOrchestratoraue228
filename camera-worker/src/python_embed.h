#ifndef PYTHON_EMBED_H
#define PYTHON_EMBED_H

#include <stddef.h>
#include <stdint.h>

typedef struct {
    int ok;
    char status[32];
    double anomaly_score;
    char message[256];
} py_detect_result_t;

int py_embed_init(const char *module_name, const char *function_name, const char *python_path, const char *detector_id,
                  char *err, size_t err_len);
int py_embed_set_reference(const uint8_t *frame, int width, int height, int stride, const char *product_type, char *err,
                           size_t err_len);
int py_embed_detect(const uint8_t *frame, int width, int height, int stride, uint64_t frame_id, py_detect_result_t *out,
                    char *err, size_t err_len);
void py_embed_shutdown(void);
const char *py_embed_python_version(void);

#endif
