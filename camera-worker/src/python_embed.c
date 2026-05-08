#define NPY_NO_DEPRECATED_API NPY_1_7_API_VERSION
#include "python_embed.h"

#include <Python.h>
#include <numpy/arrayobject.h>

#include <stdio.h>
#include <string.h>

static int g_initialized = 0;
static PyObject *g_module = NULL;
static PyObject *g_func = NULL;
static PyObject *g_set_reference_func = NULL;
static char g_python_version[64] = "unknown";

static void set_error(char *err, size_t err_len, const char *msg) {
    if (!err || err_len == 0) return;
    snprintf(err, err_len, "%s", msg ? msg : "unknown_error");
}

static void set_python_exception(char *err, size_t err_len) {
    if (!PyErr_Occurred()) {
        set_error(err, err_len, "python_error");
        return;
    }
    PyObject *ptype = NULL;
    PyObject *pvalue = NULL;
    PyObject *ptrace = NULL;
    PyErr_Fetch(&ptype, &pvalue, &ptrace);
    PyErr_NormalizeException(&ptype, &pvalue, &ptrace);
    PyObject *value_str = pvalue ? PyObject_Str(pvalue) : NULL;
    const char *msg = value_str ? PyUnicode_AsUTF8(value_str) : "python_exception";
    set_error(err, err_len, msg);
    Py_XDECREF(value_str);
    Py_XDECREF(ptype);
    Py_XDECREF(pvalue);
    Py_XDECREF(ptrace);
}

int py_embed_init(const char *module_name, const char *function_name, const char *python_path, const char *detector_id,
                  char *err, size_t err_len) {
    if (g_initialized) return 0;
    if (!module_name || !function_name) {
        set_error(err, err_len, "module_or_function_missing");
        return -1;
    }

    Py_Initialize();
    if (!Py_IsInitialized()) {
        set_error(err, err_len, "py_initialize_failed");
        return -1;
    }

    const char *ver = Py_GetVersion();
    if (ver) snprintf(g_python_version, sizeof(g_python_version), "%s", ver);

    if (detector_id && detector_id[0] != '\0') {
        setenv("IML_DETECTOR_ID", detector_id, 1);
    }

    if (_import_array() < 0) {
        set_python_exception(err, err_len);
        Py_Finalize();
        return -1;
    }

    if (python_path && python_path[0] != '\0') {
        PyObject *sys_path = PySys_GetObject("path");
        if (!sys_path) {
            set_error(err, err_len, "python_sys_path_missing");
            Py_Finalize();
            return -1;
        }
        char paths_copy[2048];
        snprintf(paths_copy, sizeof(paths_copy), "%s", python_path);
        char *saveptr = NULL;
        char *token = strtok_r(paths_copy, ";", &saveptr);
        while (token) {
            if (token[0] != '\0') {
                PyObject *p = PyUnicode_FromString(token);
                if (!p || PyList_Append(sys_path, p) != 0) {
                    Py_XDECREF(p);
                    set_python_exception(err, err_len);
                    Py_Finalize();
                    return -1;
                }
                Py_DECREF(p);
            }
            token = strtok_r(NULL, ";", &saveptr);
        }
    }

    g_module = PyImport_ImportModule(module_name);
    if (!g_module) {
        set_python_exception(err, err_len);
        Py_Finalize();
        return -1;
    }

    g_func = PyObject_GetAttrString(g_module, function_name);
    if (!g_func || !PyCallable_Check(g_func)) {
        set_error(err, err_len, "python_function_not_callable");
        Py_XDECREF(g_func);
        Py_DECREF(g_module);
        g_func = NULL;
        g_module = NULL;
        Py_Finalize();
        return -1;
    }
    g_set_reference_func = PyObject_GetAttrString(g_module, "set_reference");
    if (g_set_reference_func && !PyCallable_Check(g_set_reference_func)) {
        Py_DECREF(g_set_reference_func);
        g_set_reference_func = NULL;
    }
    PyObject *set_detector_func = PyObject_GetAttrString(g_module, "set_detector");
    if (set_detector_func && PyCallable_Check(set_detector_func) && detector_id && detector_id[0] != '\0') {
        PyObject *det_id = PyUnicode_FromString(detector_id);
        PyObject *set_result = PyObject_CallFunctionObjArgs(set_detector_func, det_id, NULL);
        Py_XDECREF(set_result);
        Py_XDECREF(det_id);
        if (PyErr_Occurred()) {
            PyErr_Clear();
        }
    }
    Py_XDECREF(set_detector_func);

    g_initialized = 1;
    return 0;
}

int py_embed_set_reference(const uint8_t *frame, int width, int height, int stride, const char *product_type, char *err,
                           size_t err_len) {
    if (!g_initialized || !g_set_reference_func) {
        return 0;
    }
    npy_intp dims[3] = {height, width, 3};
    npy_intp strides[3] = {stride, 3, 1};
    PyObject *arr = PyArray_New(&PyArray_Type, 3, dims, NPY_UINT8, strides, (void *)frame, 0,
                                NPY_ARRAY_C_CONTIGUOUS | NPY_ARRAY_ALIGNED, NULL);
    if (!arr) {
        set_python_exception(err, err_len);
        return -1;
    }
    PyObject *pt = PyUnicode_FromString(product_type ? product_type : "default");
    PyObject *result = NULL;
    if (pt) {
        result = PyObject_CallFunctionObjArgs(g_set_reference_func, arr, pt, NULL);
    } else {
        result = PyObject_CallFunctionObjArgs(g_set_reference_func, arr, NULL);
    }
    Py_XDECREF(pt);
    Py_DECREF(arr);
    if (!result) {
        set_python_exception(err, err_len);
        return -1;
    }
    Py_DECREF(result);
    return 0;
}

int py_embed_detect(const uint8_t *frame, int width, int height, int stride, uint64_t frame_id, py_detect_result_t *out,
                    char *err, size_t err_len) {
    if (!g_initialized || !g_func) {
        set_error(err, err_len, "python_not_initialized");
        return -1;
    }
    if (!frame || !out) {
        set_error(err, err_len, "invalid_input");
        return -1;
    }

    memset(out, 0, sizeof(*out));
    snprintf(out->status, sizeof(out->status), "ERROR");
    snprintf(out->message, sizeof(out->message), "unknown");

    npy_intp dims[3] = {height, width, 3};
    npy_intp strides[3] = {stride, 3, 1};
    PyObject *arr = PyArray_New(&PyArray_Type, 3, dims, NPY_UINT8, strides, (void *)frame, 0,
                                NPY_ARRAY_C_CONTIGUOUS | NPY_ARRAY_ALIGNED, NULL);
    if (!arr) {
        set_python_exception(err, err_len);
        return -1;
    }

    PyObject *frame_id_obj = PyLong_FromUnsignedLongLong((unsigned long long)frame_id);
    PyObject *result = NULL;
    if (frame_id_obj) {
        result = PyObject_CallFunctionObjArgs(g_func, arr, frame_id_obj, NULL);
    } else {
        PyErr_Clear();
        result = PyObject_CallFunctionObjArgs(g_func, arr, NULL);
    }
    Py_XDECREF(frame_id_obj);
    Py_DECREF(arr);

    if (!result) {
        set_python_exception(err, err_len);
        return -1;
    }

    if (!PyDict_Check(result)) {
        Py_DECREF(result);
        set_error(err, err_len, "python_result_not_dict");
        return -1;
    }

    PyObject *ok_obj = PyDict_GetItemString(result, "ok");
    PyObject *status_obj = PyDict_GetItemString(result, "status");
    PyObject *score_obj = PyDict_GetItemString(result, "anomaly_score");
    PyObject *msg_obj = PyDict_GetItemString(result, "message");

    out->ok = ok_obj ? PyObject_IsTrue(ok_obj) : 0;
    if (status_obj) {
        const char *s = PyUnicode_AsUTF8(status_obj);
        if (s) snprintf(out->status, sizeof(out->status), "%s", s);
    }
    if (score_obj) {
        out->anomaly_score = PyFloat_AsDouble(score_obj);
        if (PyErr_Occurred()) {
            PyErr_Clear();
            out->anomaly_score = 0.0;
        }
    }
    if (msg_obj) {
        const char *m = PyUnicode_AsUTF8(msg_obj);
        if (m) snprintf(out->message, sizeof(out->message), "%s", m);
    }

    Py_DECREF(result);
    return 0;
}

void py_embed_shutdown(void) {
    if (!g_initialized) return;
    Py_XDECREF(g_set_reference_func);
    Py_XDECREF(g_func);
    Py_XDECREF(g_module);
    g_set_reference_func = NULL;
    g_func = NULL;
    g_module = NULL;
    g_initialized = 0;
    if (Py_IsInitialized()) {
        Py_Finalize();
    }
}

const char *py_embed_python_version(void) { return g_python_version; }
