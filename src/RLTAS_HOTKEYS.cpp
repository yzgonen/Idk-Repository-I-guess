#include "pch.hpp"
#include "RLTAS_CORE.hpp"
static RLTAS::Core g_TAS;
static bool g_MenuOpen=false;
static HWND g_MenuWnd=nullptr;

static LRESULT CALLBACK RLTAS_MenuProc(HWND hwnd,UINT msg,WPARAM wp,LPARAM lp){
 if(msg==WM_PAINT){
  PAINTSTRUCT ps{}; HDC dc=BeginPaint(hwnd,&ps);
  RECT rc{}; GetClientRect(hwnd,&rc);
  FillRect(dc,&rc,(HBRUSH)(COLOR_WINDOW+1));
  SetBkMode(dc,TRANSPARENT);
  std::wstring status=L"RLTAS  |  Freeplay only\r\n\r\n";
  status+=g_TAS.recording?L"Recording: ON\r\n":L"Recording: OFF\r\n";
  status+=g_TAS.saved.valid?L"Saved state: READY\r\n":L"Saved state: EMPTY\r\n";
  status+=L"Gravity level: "+std::to_wstring(g_TAS.gravityLevel)+L"\r\n";
  status+=L"Collision/Bounce level: "+std::to_wstring(g_TAS.bounceLevel)+L"\r\n";
  status+=L"Recordings: "+RLTAS::Core::RecordingDirectory().wstring()+L"\r\n\r\n";
  status+=L"F9  Open / close menu\r\n";
  status+=L"5   Start / stop + save recording\r\n";
  status+=L"9   Cancel recording\r\n";
  status+=L"R   Save exact car + ball state\r\n";
  status+=L"Mouse 5   Return to saved state\r\n";
  status+=L"Mouse 4   Rewind 1 second\r\n";
  status+=L"6 / 7   Gravity down / up\r\n";
  status+=L"F / G   Collision response up / down\r\n";
  status+=L"8   Reset physics";
  DrawTextW(dc,status.c_str(),-1,&rc,DT_LEFT|DT_TOP|DT_NOPREFIX);
  EndPaint(hwnd,&ps); return 0;
 }
 if(msg==WM_CLOSE){ShowWindow(hwnd,SW_HIDE);g_MenuOpen=false;return 0;}
 return DefWindowProcW(hwnd,msg,wp,lp);
}

static void EnsureMenu(){
 if(g_MenuWnd)return;
 HINSTANCE inst=GetModuleHandleW(nullptr);
 WNDCLASSW wc{}; wc.lpfnWndProc=RLTAS_MenuProc; wc.hInstance=inst; wc.lpszClassName=L"RLTASStatusWindow"; wc.hCursor=LoadCursor(nullptr,IDC_ARROW);
 RegisterClassW(&wc);
 g_MenuWnd=CreateWindowExW(WS_EX_TOPMOST|WS_EX_TOOLWINDOW,wc.lpszClassName,L"RLTAS",WS_OVERLAPPED|WS_CAPTION|WS_SYSMENU,80,80,430,390,nullptr,nullptr,inst,nullptr);
}
static void PumpMenuMessages(){
 if(!g_MenuWnd)return;
 MSG msg{};
 while(PeekMessageW(&msg,g_MenuWnd,0,0,PM_REMOVE)){TranslateMessage(&msg);DispatchMessageW(&msg);}
}
static bool edge(int vk){static SHORT old[256]{};SHORT n=GetAsyncKeyState(vk);bool hit=(n&0x8000)&&!(old[vk]&0x8000);old[vk]=n;return hit;}
void RLTAS_HotkeyTick(){g_TAS.Tick();if(edge(VK_F9)){g_MenuOpen=!g_MenuOpen;EnsureMenu();ShowWindow(g_MenuWnd,g_MenuOpen?SW_SHOW:SW_HIDE);}if(g_MenuWnd&&g_MenuOpen)InvalidateRect(g_MenuWnd,nullptr,FALSE);PumpMenuMessages();if(!g_TAS.enabled)return;if(edge('R'))g_TAS.SaveNow();if(edge(VK_XBUTTON2))g_TAS.RestoreSaved();if(edge(VK_XBUTTON1))g_TAS.RewindOneSecond();if(edge('6')){g_TAS.gravityLevel=std::max(-1,g_TAS.gravityLevel-1);g_TAS.ApplyPhysics();}if(edge('7')){g_TAS.gravityLevel=std::min(2,g_TAS.gravityLevel+1);g_TAS.ApplyPhysics();}if(edge('F')){g_TAS.bounceLevel=std::min(2,g_TAS.bounceLevel+1);g_TAS.ApplyPhysics();}if(edge('G')){g_TAS.bounceLevel=std::max(-1,g_TAS.bounceLevel-1);g_TAS.ApplyPhysics();}if(edge('8'))g_TAS.ResetPhysics();if(edge('5'))g_TAS.ToggleRecording();if(edge('9'))g_TAS.CancelRecording();}

void RLTAS_CanvasDraw(UCanvas* canvas){
 if(!canvas||!g_MenuOpen)return;
 // HUD rendering is intentionally routed here on the game thread.
 // Text/box primitives will be added once the exact generated UCanvas API is validated.
}
