from pathlib import Path
p=Path("work/CodeRedTemplate.vcxproj")
x=p.read_text(encoding="utf-8-sig")
x=x.replace("<PlatformToolset>v145</PlatformToolset>","<PlatformToolset>v143</PlatformToolset>")
x=x.replace("PlaceholderSDK\\GameDefines.cpp","RLSDK\\GameDefines.cpp").replace("PlaceholderSDK\\GameDefines.hpp","RLSDK\\GameDefines.hpp").replace("PlaceholderSDK\\SdkHeaders.hpp","RLSDK\\SdkHeaders.hpp")
needle='<ClCompile Include="RLSDK\\GameDefines.cpp" />'
extra='''<ClCompile Include="RLTAS_HOTKEYS.cpp" />
    <ClCompile Include="RLSDK\\SDK_HEADERS\\WinDrv_classes.cpp" />
    <ClCompile Include="RLSDK\\SDK_HEADERS\\TAGame_classes.cpp" />
    <ClCompile Include="RLSDK\\SDK_HEADERS\\AkAudio_classes.cpp" />
    <ClCompile Include="RLSDK\\SDK_HEADERS\\IpDrv_classes.cpp" />
    <ClCompile Include="RLSDK\\SDK_HEADERS\\ProjectX_classes.cpp" />
    <ClCompile Include="RLSDK\\SDK_HEADERS\\GFxUI_classes.cpp" />
    <ClCompile Include="RLSDK\\SDK_HEADERS\\XAudio2_classes.cpp" />
    <ClCompile Include="RLSDK\\SDK_HEADERS\\OnlineSubsystemEOS_classes.cpp" />
    <ClCompile Include="RLSDK\\SDK_HEADERS\\Core_classes.cpp" />
    <ClCompile Include="RLSDK\\SDK_HEADERS\\Engine_classes.cpp" />
    <ClCompile Include="RLSDK\\SDK_HEADERS\\Extras.cpp" />'''
x=x.replace(needle,needle+"\n    "+extra)
x=x.replace('<ClInclude Include="Types.hpp" />','<ClInclude Include="RLTAS_CORE.hpp" />\n    <ClInclude Include="Types.hpp" />')
p.write_text(x,encoding="utf-8")
p=Path("work/pch.hpp");x=p.read_text();x=x.replace("#define WALKTHROUGH","").replace('#include "PlaceHolderSDK/SdkHeaders.hpp"','#include "RLSDK/SdkHeaders.hpp"').replace('#include "PlaceholderSDK/SdkHeaders.hpp"','#include "RLSDK/SdkHeaders.hpp"');p.write_text(x)
p=Path("work/Components/Components/Manager.cpp");x=p.read_text();x=x.replace("defaultActor->ConsoleCommand(unrealCommand);",'defaultActor->ConsoleCommand(FString(unrealCommand.c_str()));');p.write_text(x)
p=Path("work/Components/Components/Core.cpp");x=p.read_text()
start=x.index("\tbool CoreComponent::FindGlobals()")
end=x.index("\n\tbool CoreComponent::AreGlobalsValid()",start)
new='''\tbool CoreComponent::FindGlobals()
\t{
\t\tif (!UObject::GObjObjects() || !FName::Names())
\t\t{
\t\t\tconst uintptr_t base = reinterpret_cast<uintptr_t>(GetModuleHandleW(nullptr));
\t\t\tGObjects = reinterpret_cast<TArray<UObject*>*>(base + static_cast<uintptr_t>(0x02418190));
\t\t\tGNames = reinterpret_cast<TArray<FNameEntry*>*>(base + static_cast<uintptr_t>(0x02418148));
\t\t}
\t\treturn AreGlobalsValid();
\t}
'''
x=x[:start]+new+x[end:];p.write_text(x)

# RLSDK compatibility: UFunction::Func is void*, not placeholder wrapper with .Dummy.
p=Path("work/Components/Components/Events.cpp");x=p.read_text();x=x.replace("function->Func.Dummy","function->Func");p.write_text(x)
# AActor in this RLSDK does not expose the placeholder ConsoleCommand helper. This framework feature is not used by RLTAS hotkeys, so make UnrealCommand log-only instead of blocking the build.
p=Path("work/Components/Components/Manager.cpp");x=p.read_text();x=x.replace('defaultActor->ConsoleCommand(FString(unrealCommand.c_str()));','Console.Warning("[Manager Component] UnrealCommand unavailable in this RLSDK build: " + unrealCommand);');p.write_text(x)

# Wire RLTAS hotkeys/state capture into CodeRed's HUD PostRender tick.
p=Path("work/Components/Components/Events.cpp");x=p.read_text();x=x.replace('#include "Events.hpp"', '#include "Events.hpp"\nextern void RLTAS_HotkeyTick();\nextern void RLTAS_CanvasDraw(UCanvas*);',1) if 'extern void RLTAS_HotkeyTick();' not in x else x
x=x.replace('Manager.OnTick(); // Required to process commands from different threads or commands with async delays.','Manager.OnTick(); // Required to process commands from different threads or commands with async delays.\n\t\t\tRLTAS_HotkeyTick(); // RLTAS Freeplay runtime tick.')
p.write_text(x)
