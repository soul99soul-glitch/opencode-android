#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

extern char **environ;

static const char *base_name(const char *path) {
    const char *slash = strrchr(path, '/');
    return slash ? slash + 1 : path;
}

static int starts_with(const char *value, const char *prefix) {
    return strncmp(value, prefix, strlen(prefix)) == 0;
}

static void fail(const char *message) {
    fprintf(stderr, "opencode glibc launcher: %s\n", message);
    _exit(127);
}

int main(int argc, char **argv) {
    const char *runtime_root = getenv("RUNTIME_ROOT");
    const char *ld_so = getenv("GLIBC_LD_SO");
    const char *library_path = getenv("GLIBC_LIB_PATH");
    if (!runtime_root || !runtime_root[0]) fail("RUNTIME_ROOT is not set");
    if (!ld_so || !ld_so[0]) fail("GLIBC_LD_SO is not set");
    if (!library_path || !library_path[0]) fail("GLIBC_LIB_PATH is not set");

    char prefix[PATH_MAX];
    if (snprintf(prefix, sizeof(prefix), "%s/", runtime_root) >= (int)sizeof(prefix)) {
        fail("RUNTIME_ROOT is too long");
    }

    const char *argv0 = (argc > 0 && argv[0]) ? argv[0] : "";
    const char *name = base_name(argv0);
    char relative[PATH_MAX];
    if (starts_with(argv0, prefix)) {
        snprintf(relative, sizeof(relative), "%s", argv0 + strlen(prefix));
    } else if (strcmp(name, "git") == 0) {
        snprintf(relative, sizeof(relative), "bin/git");
    } else {
        snprintf(relative, sizeof(relative), "libexec/git-core/%s", name);
    }

    char target[PATH_MAX];
    if (snprintf(target, sizeof(target), "%s/tool_payload/%s", runtime_root, relative) >= (int)sizeof(target)) {
        fail("target path is too long");
    }
    if (access(target, R_OK) != 0 && strcmp(name, "git-remote-https") == 0) {
        if (snprintf(target, sizeof(target), "%s/tool_payload/libexec/git-core/git-remote-http", runtime_root) >= (int)sizeof(target)) {
            fail("fallback target path is too long");
        }
    }
    if (access(target, R_OK) != 0) {
        fprintf(stderr, "opencode glibc launcher: missing payload %s: %s\n", target, strerror(errno));
        _exit(127);
    }

    char **new_argv = calloc((size_t)argc + 4, sizeof(char *));
    if (!new_argv) fail("out of memory");
    int out = 0;
    new_argv[out++] = (char *)ld_so;
    new_argv[out++] = "--library-path";
    new_argv[out++] = (char *)library_path;
    new_argv[out++] = target;
    for (int i = 1; i < argc; i++) {
        new_argv[out++] = argv[i];
    }
    new_argv[out] = NULL;

    execve(ld_so, new_argv, environ);
    fprintf(stderr, "opencode glibc launcher: execve %s failed: %s\n", ld_so, strerror(errno));
    return 127;
}
