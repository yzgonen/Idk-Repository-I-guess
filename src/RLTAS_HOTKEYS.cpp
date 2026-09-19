#include "pch.hpp"
#include "RLTAS_CORE.hpp"
static RLTAS::Core g_TAS;
static bool g_MenuOpen=false;
static bool edge(int vk){static SHORT old[256]{};SHORT n=GetAsyncKeyState(vk);bool hit=(n&0x8000)&&!(old[vk]&0x8000);old[vk]=n;return hit;}
void RLTAS_HotkeyTick(){g_TAS.Tick();if(edge(VK_F9))g_MenuOpen=!g_MenuOpen;if(!g_TAS.enabled)return;if(edge('R'))g_TAS.SaveNow();if(edge(VK_XBUTTON2))g_TAS.RestoreSaved();if(edge(VK_XBUTTON1))g_TAS.RewindOneSecond();if(edge('6')){g_TAS.gravityLevel=std::max(-1,g_TAS.gravityLevel-1);g_TAS.ApplyPhysics();}if(edge('7')){g_TAS.gravityLevel=std::min(2,g_TAS.gravityLevel+1);g_TAS.ApplyPhysics();}if(edge('F')){g_TAS.bounceLevel=std::min(2,g_TAS.bounceLevel+1);g_TAS.ApplyPhysics();}if(edge('G')){g_TAS.bounceLevel=std::max(-2,g_TAS.bounceLevel-1);g_TAS.ApplyPhysics();}if(edge('8'))g_TAS.ResetPhysics();if(edge('5'))g_TAS.ToggleRecording();if(edge('9'))g_TAS.CancelRecording();}
