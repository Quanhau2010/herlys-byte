#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#include <windows.h>
#include <shlobj.h>
#include <wchar.h>
#include <stdio.h>

#define IDR_HERLYSBYTE_JAR 101
#define CACHE_VERSION L"1.0.0"

static BOOL is_file(const wchar_t *path) {
    DWORD attr = GetFileAttributesW(path);
    return attr != INVALID_FILE_ATTRIBUTES && !(attr & FILE_ATTRIBUTE_DIRECTORY);
}

static BOOL registry_java_home(HKEY root, REGSAM view, const wchar_t *branch, wchar_t *out, size_t capacity) {
    wchar_t version[128] = {0};
    wchar_t key_name[512] = {0};
    DWORD bytes = sizeof(version), type = 0;
    HKEY key;
    if (RegOpenKeyExW(root, branch, 0, KEY_READ | view, &key) != ERROR_SUCCESS) return FALSE;
    LONG rc = RegQueryValueExW(key, L"CurrentVersion", NULL, &type, (BYTE *)version, &bytes);
    RegCloseKey(key);
    if (rc != ERROR_SUCCESS || type != REG_SZ || !version[0]) return FALSE;
    _snwprintf_s(key_name, _countof(key_name), _TRUNCATE, L"%s\\%s", branch, version);
    if (RegOpenKeyExW(root, key_name, 0, KEY_READ | view, &key) != ERROR_SUCCESS) return FALSE;
    bytes = (DWORD)(capacity * sizeof(wchar_t));
    rc = RegQueryValueExW(key, L"JavaHome", NULL, &type, (BYTE *)out, &bytes);
    if (rc != ERROR_SUCCESS) {
        bytes = (DWORD)(capacity * sizeof(wchar_t));
        rc = RegQueryValueExW(key, L"RuntimeLib", NULL, &type, (BYTE *)out, &bytes);
        if (rc == ERROR_SUCCESS) {
            wchar_t *last = wcsrchr(out, L'\\');
            if (last) *last = L'\0';
            last = wcsrchr(out, L'\\');
            if (last) *last = L'\0';
        }
    }
    RegCloseKey(key);
    if (rc != ERROR_SUCCESS || !out[0]) return FALSE;
    wchar_t candidate[32768];
    _snwprintf_s(candidate, _countof(candidate), _TRUNCATE, L"%s\\bin\\javaw.exe", out);
    if (is_file(candidate)) { wcscpy_s(out, capacity, candidate); return TRUE; }
    return is_file(out);
}

static BOOL java_in_vendor_folder(const wchar_t *program_files, const wchar_t *vendor, const wchar_t *glob, wchar_t *out, size_t capacity) {
    if (!program_files[0]) return FALSE;
    wchar_t pattern[32768];
    _snwprintf_s(pattern, _countof(pattern), _TRUNCATE, L"%s\\%s\\%s", program_files, vendor, glob);
    WIN32_FIND_DATAW data;
    HANDLE search = FindFirstFileW(pattern, &data);
    if (search == INVALID_HANDLE_VALUE) return FALSE;
    BOOL found = FALSE;
    do {
        if (!(data.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) continue;
        wchar_t candidate[32768];
        _snwprintf_s(candidate, _countof(candidate), _TRUNCATE, L"%s\\%s\\%s\\bin\\javaw.exe", program_files, vendor, data.cFileName);
        if (is_file(candidate)) { wcscpy_s(out, capacity, candidate); found = TRUE; break; }
    } while (FindNextFileW(search, &data));
    FindClose(search);
    return found;
}

static BOOL locate_java(wchar_t *out, size_t capacity) {
    wchar_t home[32768] = {0};
    DWORD n = GetEnvironmentVariableW(L"JAVA_HOME", home, _countof(home));
    if (n > 0 && n < _countof(home)) {
        _snwprintf_s(out, capacity, _TRUNCATE, L"%s\\bin\\javaw.exe", home);
        if (is_file(out)) return TRUE;
    }
    DWORD found = SearchPathW(NULL, L"javaw.exe", NULL, (DWORD)capacity, out, NULL);
    if (found > 0 && found < capacity && is_file(out)) return TRUE;
    const wchar_t *branches[] = { L"SOFTWARE\\JavaSoft\\JDK", L"SOFTWARE\\JavaSoft\\JRE", L"SOFTWARE\\JavaSoft\\Java Development Kit" };
    for (unsigned i = 0; i < sizeof(branches) / sizeof(branches[0]); ++i) {
        if (registry_java_home(HKEY_LOCAL_MACHINE, KEY_WOW64_64KEY, branches[i], out, capacity)) return TRUE;
        if (registry_java_home(HKEY_LOCAL_MACHINE, KEY_WOW64_32KEY, branches[i], out, capacity)) return TRUE;
        if (registry_java_home(HKEY_CURRENT_USER, 0, branches[i], out, capacity)) return TRUE;
    }
    wchar_t pf[32768] = {0}, pf32[32768] = {0};
    GetEnvironmentVariableW(L"ProgramFiles", pf, _countof(pf));
    GetEnvironmentVariableW(L"ProgramFiles(x86)", pf32, _countof(pf32));
    const wchar_t *roots[] = { pf, pf32 };
    const wchar_t *vendors[] = { L"Eclipse Adoptium", L"Java", L"Microsoft", L"Amazon Corretto" };
    wchar_t pattern[32];
    for (int major = 21; major <= 39; ++major) for (unsigned r = 0; r < 2; ++r) for (unsigned v = 0; v < 4; ++v) {
        _snwprintf_s(pattern, _countof(pattern), _TRUNCATE, L"jdk-%d*", major);
        if (java_in_vendor_folder(roots[r], vendors[v], pattern, out, capacity)) return TRUE;
        _snwprintf_s(pattern, _countof(pattern), _TRUNCATE, L"jdk%d*", major);
        if (java_in_vendor_folder(roots[r], vendors[v], pattern, out, capacity)) return TRUE;
    }
    return FALSE;
}

static BOOL unpack_jar(wchar_t *jar_path, size_t capacity) {
    wchar_t local[32768] = {0};
    DWORD n = GetEnvironmentVariableW(L"LOCALAPPDATA", local, _countof(local));
    if (!n || n >= _countof(local)) return FALSE;
    wchar_t app_dir[32768], version_dir[32768];
    _snwprintf_s(app_dir, _countof(app_dir), _TRUNCATE, L"%s\\HerlysByte", local);
    CreateDirectoryW(app_dir, NULL);
    _snwprintf_s(version_dir, _countof(version_dir), _TRUNCATE, L"%s\\%s", app_dir, CACHE_VERSION);
    CreateDirectoryW(version_dir, NULL);
    _snwprintf_s(jar_path, capacity, _TRUNCATE, L"%s\\HerlysByte-Windows.jar", version_dir);

    HRSRC resource = FindResourceW(NULL, MAKEINTRESOURCEW(IDR_HERLYSBYTE_JAR), RT_RCDATA);
    if (!resource) return FALSE;
    HGLOBAL loaded = LoadResource(NULL, resource);
    DWORD size = SizeofResource(NULL, resource);
    const void *data = loaded ? LockResource(loaded) : NULL;
    if (!data || !size) return FALSE;

    WIN32_FILE_ATTRIBUTE_DATA existing;
    if (GetFileAttributesExW(jar_path, GetFileExInfoStandard, &existing)) {
        ULONGLONG current_size = ((ULONGLONG)existing.nFileSizeHigh << 32) | existing.nFileSizeLow;
        if (current_size == size) return TRUE;
    }
    HANDLE file = CreateFileW(jar_path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) return FALSE;
    const BYTE *cursor = (const BYTE *)data;
    DWORD remaining = size;
    BOOL ok = TRUE;
    while (remaining) {
        DWORD chunk = remaining > 1024 * 1024 ? 1024 * 1024 : remaining;
        DWORD written = 0;
        if (!WriteFile(file, cursor, chunk, &written, NULL) || written != chunk) { ok = FALSE; break; }
        cursor += written; remaining -= written;
    }
    CloseHandle(file);
    if (!ok) DeleteFileW(jar_path);
    return ok;
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR command_line, int show) {
    (void)instance; (void)previous; (void)command_line; (void)show;
    wchar_t javaw[32768] = {0}, jar_path[32768] = {0};
    if (!locate_java(javaw, _countof(javaw))) {
        MessageBoxW(NULL, L"HerlysByte cần Java 21 trở lên. Hãy cài Java 21+, sau đó chạy lại file EXE.\n\nTải Java: https://adoptium.net/temurin/releases/?version=21", L"HerlysByte — cần cài Java", MB_OK | MB_ICONINFORMATION);
        return 1;
    }
    if (!unpack_jar(jar_path, _countof(jar_path))) {
        MessageBoxW(NULL, L"Không thể chuẩn bị HerlysByte. Hãy chạy lại file EXE với quyền ghi vào thư mục Local AppData.", L"HerlysByte — lỗi khởi chạy", MB_OK | MB_ICONERROR);
        return 2;
    }
    wchar_t command[65536];
    _snwprintf_s(command, _countof(command), _TRUNCATE, L"\"%s\" -jar \"%s\"", javaw, jar_path);
    STARTUPINFOW startup = {0}; startup.cb = sizeof(startup);
    PROCESS_INFORMATION process = {0};
    if (!CreateProcessW(javaw, command, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, NULL, &startup, &process)) {
        wchar_t message[512]; _snwprintf_s(message, _countof(message), _TRUNCATE, L"Không khởi chạy được Java (lỗi %lu). Hãy kiểm tra cài đặt Java 21+.", GetLastError());
        MessageBoxW(NULL, message, L"HerlysByte — lỗi khởi chạy", MB_OK | MB_ICONERROR);
        return 3;
    }
    CloseHandle(process.hThread); CloseHandle(process.hProcess);
    return 0;
}
