#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define PROBE_VERSION "opencode-runtime-probe 0.1.0"

typedef int (*dummy_fn)(void);
typedef int (*jit_fn)(void);

typedef struct {
    bool ok;
    long page_size;
    char proc_self_exe[512];
    bool dlopen_ok;
    bool mmap_file_exec_ok;
    bool execmem_ok;
    char error[512];
} probe_result_t;

static void append_error(probe_result_t *result, const char *fmt, ...) {
    if (result->error[0] != '\0') {
        strncat(result->error, "; ", sizeof(result->error) - strlen(result->error) - 1);
    }
    va_list args;
    va_start(args, fmt);
    size_t used = strlen(result->error);
    vsnprintf(result->error + used, sizeof(result->error) - used, fmt, args);
    va_end(args);
}

static void read_proc_self_exe(probe_result_t *result) {
    ssize_t len = readlink("/proc/self/exe", result->proc_self_exe, sizeof(result->proc_self_exe) - 1);
    if (len < 0) {
        snprintf(result->proc_self_exe, sizeof(result->proc_self_exe), "readlink failed: %s", strerror(errno));
        append_error(result, "/proc/self/exe readlink failed: %s", strerror(errno));
        return;
    }
    result->proc_self_exe[len] = '\0';
}

static bool run_dlopen_check(const char *runtime_root, probe_result_t *result) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/lib/probe/libopencode_probe_dummy.so", runtime_root);
    void *handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (handle == NULL) {
        append_error(result, "dlopen %s failed: %s", path, dlerror());
        return false;
    }
    dummy_fn fn = (dummy_fn)dlsym(handle, "opencode_probe_dummy");
    if (fn == NULL) {
        append_error(result, "dlsym opencode_probe_dummy failed: %s", dlerror());
        dlclose(handle);
        return false;
    }
    int value = fn();
    dlclose(handle);
    if (value != 42) {
        append_error(result, "dummy function returned %d", value);
        return false;
    }
    return true;
}

static bool run_mmap_file_exec_check(const char *runtime_root, probe_result_t *result) {
    char path[1024];
    snprintf(path, sizeof(path), "%s/lib/probe/libopencode_probe_dummy.so", runtime_root);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        append_error(result, "open dummy so failed: %s", strerror(errno));
        return false;
    }
    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        append_error(result, "stat dummy so failed: %s", strerror(errno));
        close(fd);
        return false;
    }
    size_t map_size = (size_t)st.st_size;
    void *mapped = mmap(NULL, map_size, PROT_READ | PROT_EXEC, MAP_PRIVATE, fd, 0);
    close(fd);
    if (mapped == MAP_FAILED) {
        append_error(result, "mmap file PROT_EXEC failed: %s", strerror(errno));
        return false;
    }
    munmap(mapped, map_size);
    return true;
}

static bool run_execmem_check(probe_result_t *result) {
#if defined(__aarch64__)
    unsigned char code[] = {
        0x40, 0x05, 0x80, 0x52, /* mov w0, #42 */
        0xc0, 0x03, 0x5f, 0xd6, /* ret */
    };
    size_t page_size = (size_t)sysconf(_SC_PAGESIZE);
    void *mem = mmap(NULL, page_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (mem == MAP_FAILED) {
        append_error(result, "anonymous mmap failed: %s", strerror(errno));
        return false;
    }
    memcpy(mem, code, sizeof(code));
    __builtin___clear_cache((char *)mem, (char *)mem + sizeof(code));
    if (mprotect(mem, page_size, PROT_READ | PROT_EXEC) != 0) {
        append_error(result, "mprotect PROT_EXEC failed: %s", strerror(errno));
        munmap(mem, page_size);
        return false;
    }
    int value = ((jit_fn)mem)();
    munmap(mem, page_size);
    if (value != 42) {
        append_error(result, "execmem function returned %d", value);
        return false;
    }
    return true;
#else
    append_error(result, "execmem probe only supports aarch64");
    return false;
#endif
}

static probe_result_t run_phase0a2_probe(void) {
    probe_result_t result;
    memset(&result, 0, sizeof(result));
    result.page_size = sysconf(_SC_PAGESIZE);
    read_proc_self_exe(&result);

    const char *runtime_root = getenv("RUNTIME_ROOT");
    if (runtime_root == NULL || runtime_root[0] == '\0') {
        append_error(&result, "RUNTIME_ROOT is not set");
    } else {
        result.dlopen_ok = run_dlopen_check(runtime_root, &result);
        result.mmap_file_exec_ok = run_mmap_file_exec_check(runtime_root, &result);
    }
    result.execmem_ok = run_execmem_check(&result);
    result.ok = result.error[0] == '\0';
    return result;
}

static void json_escape(FILE *out, const char *value) {
    fputc('"', out);
    for (const char *p = value; *p; ++p) {
        switch (*p) {
            case '\\':
            case '"':
                fputc('\\', out);
                fputc(*p, out);
                break;
            case '\n':
                fputs("\\n", out);
                break;
            case '\r':
                fputs("\\r", out);
                break;
            case '\t':
                fputs("\\t", out);
                break;
            default:
                fputc(*p, out);
        }
    }
    fputc('"', out);
}

static void write_probe_json(FILE *out, const probe_result_t *result) {
    fprintf(out, "{\"ok\":%s,\"runtime\":\"phase0-probe\",\"pageSize\":%ld,", result->ok ? "true" : "false", result->page_size);
    fprintf(out, "\"dlopen\":%s,\"mmapFileExec\":%s,\"execmem\":%s,",
            result->dlopen_ok ? "true" : "false",
            result->mmap_file_exec_ok ? "true" : "false",
            result->execmem_ok ? "true" : "false");
    fputs("\"procSelfExe\":", out);
    json_escape(out, result->proc_self_exe);
    fputs(",\"error\":", out);
    json_escape(out, result->error);
    fputc('}', out);
}

static int send_response(int client, int status, const char *content_type, const char *body) {
    char header[512];
    int body_len = (int)strlen(body);
    int header_len = snprintf(
        header,
        sizeof(header),
        "HTTP/1.1 %d %s\r\nContent-Type: %s\r\nContent-Length: %d\r\nConnection: close\r\n\r\n",
        status,
        status == 200 ? "OK" : "Not Found",
        content_type,
        body_len);
    if (write(client, header, (size_t)header_len) < 0) return -1;
    if (write(client, body, (size_t)body_len) < 0) return -1;
    return 0;
}

static int handle_client(int client, const probe_result_t *probe) {
    char request[2048];
    ssize_t len = read(client, request, sizeof(request) - 1);
    if (len <= 0) return -1;
    request[len] = '\0';

    char method[16] = {0};
    char path[256] = {0};
    sscanf(request, "%15s %255s", method, path);

    if (strcmp(path, "/global/health") == 0) {
        return send_response(client, 200, "application/json", "{\"ok\":true,\"runtime\":\"phase0-probe\"}");
    }
    if (strcmp(path, "/config/providers") == 0) {
        return send_response(
            client,
            200,
            "application/json",
            "{\"providers\":[{\"id\":\"android-local\",\"name\":\"Android Local\",\"npm\":\"@ai-sdk/openai-compatible\",\"models\":{\"phase0-probe\":{\"name\":\"phase0-probe\"}}}],\"default\":\"android-local/phase0-probe\"}");
    }
    if (strcmp(path, "/provider") == 0) {
        return send_response(
            client,
            200,
            "application/json",
            "{\"all\":[{\"id\":\"android-local\",\"name\":\"Android Local\",\"source\":\"config\",\"models\":{\"phase0-probe\":{\"name\":\"phase0-probe\"}}}],\"default\":\"android-local/phase0-probe\",\"connected\":true}");
    }
    if (strcmp(path, "/phase0/probe") == 0) {
        char body[2048];
        FILE *out = fmemopen(body, sizeof(body), "w");
        if (out == NULL) return send_response(client, 500, "text/plain", "fmemopen failed");
        write_probe_json(out, probe);
        fclose(out);
        body[sizeof(body) - 1] = '\0';
        return send_response(client, 200, "application/json", body);
    }
    return send_response(client, 404, "text/plain", "not found");
}

static int parse_port(int argc, char **argv) {
    for (int i = 0; i < argc - 1; ++i) {
        if (strcmp(argv[i], "--port") == 0) {
            return atoi(argv[i + 1]);
        }
    }
    return 4097;
}

static int serve(int argc, char **argv) {
    probe_result_t probe = run_phase0a2_probe();
    if (!probe.ok) {
        fprintf(stderr, "phase0a2 probe failed: %s\n", probe.error);
        return 70;
    }

    int port = parse_port(argc, argv);
    int server = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (server < 0) {
        perror("socket");
        return 71;
    }
    int reuse = 1;
    setsockopt(server, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    struct sockaddr_in address;
    memset(&address, 0, sizeof(address));
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    address.sin_port = htons((uint16_t)port);

    if (bind(server, (struct sockaddr *)&address, sizeof(address)) != 0) {
        perror("bind");
        close(server);
        return 72;
    }
    if (listen(server, 16) != 0) {
        perror("listen");
        close(server);
        return 73;
    }

    fprintf(stdout, "phase0 probe serving on 127.0.0.1:%d page_size=%ld\n", port, probe.page_size);
    fflush(stdout);

    while (true) {
        int client = accept4(server, NULL, NULL, SOCK_CLOEXEC);
        if (client < 0) {
            if (errno == EINTR) continue;
            perror("accept");
            break;
        }
        handle_client(client, &probe);
        close(client);
    }
    close(server);
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 2 && strcmp(argv[1], "--version") == 0) {
        probe_result_t probe = run_phase0a2_probe();
        printf("%s page_size=%ld proc_self_exe=%s probe_ok=%s\n",
               PROBE_VERSION,
               probe.page_size,
               probe.proc_self_exe,
               probe.ok ? "true" : "false");
        if (!probe.ok) {
            fprintf(stderr, "phase0a2 probe failed: %s\n", probe.error);
            return 70;
        }
        return 0;
    }
    if (argc >= 2 && strcmp(argv[1], "--phase0-probe") == 0) {
        probe_result_t probe = run_phase0a2_probe();
        write_probe_json(stdout, &probe);
        fputc('\n', stdout);
        return probe.ok ? 0 : 70;
    }
    if (argc >= 2 && strcmp(argv[1], "serve") == 0) {
        return serve(argc, argv);
    }
    fprintf(stderr, "%s\nusage: %s --version | --phase0-probe | serve --hostname 127.0.0.1 --port 4097\n", PROBE_VERSION, argv[0]);
    return 64;
}
