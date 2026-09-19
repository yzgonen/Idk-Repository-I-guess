#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <tlhelp32.h>
#include <string>
#include <iostream>
#include <filesystem>

static DWORD FindProcess(const wchar_t* name) {
    PROCESSENTRY32W pe{sizeof(pe)};
    HANDLE s=CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS,0);
    if(s==INVALID_HANDLE_VALUE) return 0;
    DWORD pid=0;
    if(Process32FirstW(s,&pe)) do {
        if(_wcsicmp(pe.szExeFile,name)==0){ pid=pe.th32ProcessID; break; }
    } while(Process32NextW(s,&pe));
    CloseHandle(s); return pid;
}
int wmain() {
    if(FindProcess(L"EasyAntiCheat_EOS.exe") || FindProcess(L"EasyAntiCheat.exe")) {
        MessageBoxW(nullptr,L"Easy Anti-Cheat is running. RLTAS is Freeplay/offline only and will not load.",L"RLTAS",MB_ICONERROR);
        return 2;
    }
    DWORD pid=FindProcess(L"RocketLeague.exe");
    if(!pid) {
        MessageBoxW(nullptr,L"Start Rocket League with EAC disabled, enter Freeplay, then run RLTAS.exe again.",L"RLTAS",MB_ICONINFORMATION);
        return 3;
    }
    std::filesystem::path dll=std::filesystem::absolute(L"RLTAS.dll");
    if(!std::filesystem::exists(dll)) {
        MessageBoxW(nullptr,L"RLTAS.dll must be in the same folder as RLTAS.exe.",L"RLTAS",MB_ICONERROR);
        return 4;
    }
    HANDLE p=OpenProcess(PROCESS_CREATE_THREAD|PROCESS_QUERY_INFORMATION|PROCESS_VM_OPERATION|PROCESS_VM_WRITE|PROCESS_VM_READ,FALSE,pid);
    if(!p){ MessageBoxW(nullptr,L"Could not open RocketLeague.exe.",L"RLTAS",MB_ICONERROR); return 5; }
    std::wstring path=dll.wstring();
    SIZE_T bytes=(path.size()+1)*sizeof(wchar_t);
    void* remote=VirtualAllocEx(p,nullptr,bytes,MEM_COMMIT|MEM_RESERVE,PAGE_READWRITE);
    if(!remote || !WriteProcessMemory(p,remote,path.c_str(),bytes,nullptr)){ CloseHandle(p); return 6; }
    auto load=(LPTHREAD_START_ROUTINE)GetProcAddress(GetModuleHandleW(L"kernel32.dll"),"LoadLibraryW");
    HANDLE t=CreateRemoteThread(p,nullptr,0,load,remote,0,nullptr);
    if(!t){ VirtualFreeEx(p,remote,0,MEM_RELEASE); CloseHandle(p); return 7; }
    WaitForSingleObject(t,10000);
    DWORD code=0; GetExitCodeThread(t,&code);
    CloseHandle(t); VirtualFreeEx(p,remote,0,MEM_RELEASE); CloseHandle(p);
    if(!code){ MessageBoxW(nullptr,L"RLTAS.dll failed to load.",L"RLTAS",MB_ICONERROR); return 8; }
    MessageBoxW(nullptr,L"RLTAS loaded. Freeplay only. F9 toggles TAS.",L"RLTAS",MB_ICONINFORMATION);
    return 0;
}
