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
p=Path("work/pch.hpp");x=p.read_text();x=x.replace("#define WALKTHROUGH","").replace('#include "PlaceholderSDK/SdkHeaders.hpp"','#include "RLSDK/SdkHeaders.hpp"');p.write_text(x)
p=Path("work/Components/Components/Manager.cpp");x=p.read_text();x=x.replace("defaultActor->ConsoleCommand(unrealCommand);",'defaultActor->ConsoleCommand(FString::create(unrealCommand), false);');p.write_text(x)
p=Path("work/Components/Components/Core.cpp");x=p.read_text()
start=x.index("\tbool CoreComponent::FindGlobals()")
end=x.index("\n\tbool CoreComponent::AreGlobalsValid()",start)
new='''\tbool CoreComponent::FindGlobals()
\t{
\t\tif (!UObject::GObjObjects() || !FName::Names())
\t\t{
\t\t\tconst uintptr_t base = reinterpret_cast<uintptr_t>(GetModuleHandleW(nullptr));
\t\t\tGObjects = reinterpret_cast<TArray<UObject*>*>(base + GOBJECTS_OFFSET);
\t\t\tGNames = reinterpret_cast<TArray<FNameEntry*>*>(base + GNAMES_OFFSET);
\t\t}
\t\treturn AreGlobalsValid();
\t}
'''
x=x[:start]+new+x[end:];p.write_text(x)
