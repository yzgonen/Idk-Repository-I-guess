#include "RLTASPlugin.h"
#include "imgui.h"
#include <algorithm>
#include <fstream>
#include <ctime>
#include <cmath>
#include <cstdint>

BAKKESMOD_PLUGIN(RLTASPlugin, "Freeplay TAS: recording, savestate, rewind and physics controls", "2.0.0-bm", PLUGINTYPE_FREEPLAY)

static const char* levels[] = {"Low","Default","High","Super High"};
static float gravityScale(int x){ static float v[]={0.5f,1.f,2.f,3.f}; return v[std::clamp(x,0,3)]; }
static float bounceScale(int x){ static float v[]={0.5f,1.f,1.5f,2.f}; return v[std::clamp(x,0,3)]; }

void RLTASPlugin::onLoad() {
    cvarManager->registerNotifier("rltas_menu",[this](std::vector<std::string>){ cvarManager->executeCommand("togglemenu rltas"); },"Toggle RLTAS menu",PERMISSION_FREEPLAY);
    cvarManager->registerNotifier("rltas_record",[this](std::vector<std::string>){ toggleRecording(); },"Start/stop recording",PERMISSION_FREEPLAY);
    cvarManager->registerNotifier("rltas_cancel",[this](std::vector<std::string>){ cancelRecording(); },"Cancel recording",PERMISSION_FREEPLAY);
    cvarManager->registerNotifier("rltas_save",[this](std::vector<std::string>){ savePoint(); },"Save state",PERMISSION_FREEPLAY);
    cvarManager->registerNotifier("rltas_restore",[this](std::vector<std::string>){ restorePoint(); },"Restore state",PERMISSION_FREEPLAY);
    cvarManager->registerNotifier("rltas_rewind",[this](std::vector<std::string>){ rewindOneSecond(); },"Rewind one second",PERMISSION_FREEPLAY);
    cvarManager->registerNotifier("rltas_gravity_down",[this](std::vector<std::string>){ setGravity(gravityLevel-1); },"Gravity -1",PERMISSION_FREEPLAY);
    cvarManager->registerNotifier("rltas_gravity_up",[this](std::vector<std::string>){ setGravity(gravityLevel+1); },"Gravity +1",PERMISSION_FREEPLAY);
    cvarManager->registerNotifier("rltas_bounce_up",[this](std::vector<std::string>){ setBounce(bounceLevel+1); },"Bounce +1",PERMISSION_FREEPLAY);
    cvarManager->registerNotifier("rltas_bounce_down",[this](std::vector<std::string>){ setBounce(bounceLevel-1); },"Bounce -1",PERMISSION_FREEPLAY);
    cvarManager->registerNotifier("rltas_reset",[this](std::vector<std::string>){ resetPhysics(); },"Reset physics",PERMISSION_FREEPLAY);
    bindKeys();
    gameWrapper->HookEvent("Function PlayerController_TA.Driving.PlayerMove",[this](std::string e){ tick(e); });
    std::filesystem::create_directories(recordingsDir());
    cvarManager->log("RLTAS BakkesMod plugin loaded (Freeplay only)");
}
void RLTASPlugin::onUnload(){ gameWrapper->UnhookEvent("Function PlayerController_TA.Driving.PlayerMove"); }
void RLTASPlugin::bindKeys(){
    const char* cmds[]={"bind F9 rltas_menu","bind Five rltas_record","bind Nine rltas_cancel","bind F rltas_bounce_up","bind G rltas_bounce_down","bind Six rltas_gravity_down","bind Seven rltas_gravity_up","bind Eight rltas_reset","bind R rltas_save","bind ThumbMouseButton2 rltas_restore","bind ThumbMouseButton rltas_rewind"};
    for(auto s:cmds) cvarManager->executeCommand(s,false);
}
bool RLTASPlugin::freeplay(ServerWrapper& s, CarWrapper& c, BallWrapper& b){
    if(!gameWrapper->IsInFreeplay()) return false;
    s=gameWrapper->GetGameEventAsServer(); if(s.IsNull()) return false;
    c=s.GetGameCar(); b=s.GetBall(); return !c.IsNull() && !b.IsNull();
}
RLTASPlugin::State RLTASPlugin::capture(ServerWrapper s,CarWrapper c,BallWrapper b){ State x; x.car=c.GetRBState(); x.ball=b.GetRBState(); x.t=s.GetSecondsElapsed(); return x; }
void RLTASPlugin::apply(const State& x,CarWrapper c,BallWrapper b){ RBState a=x.car,d=x.ball; c.SetPhysicsState(a); b.SetPhysicsState(d); }
void RLTASPlugin::tick(std::string){
    ServerWrapper s(0); CarWrapper c(0); BallWrapper b(0); if(!freeplay(s,c,b)) return;
    float now=s.GetSecondsElapsed();
    if(now-lastHistory>=0.008f){ history.push_back(capture(s,c,b)); lastHistory=now; while(!history.empty() && now-history.front().t>1.35f) history.pop_front(); }
    if(recordingOn && now-lastRecording>=0.008f){ recording.push_back(capture(s,c,b)); lastRecording=now; }
}
void RLTASPlugin::savePoint(){ ServerWrapper s(0);CarWrapper c(0);BallWrapper b(0);if(freeplay(s,c,b)){saved=capture(s,c,b);hasSaved=true;} }
void RLTASPlugin::restorePoint(){ ServerWrapper s(0);CarWrapper c(0);BallWrapper b(0);if(hasSaved&&freeplay(s,c,b)) apply(saved,c,b); }
void RLTASPlugin::rewindOneSecond(){
    ServerWrapper s(0);CarWrapper c(0);BallWrapper b(0);if(!freeplay(s,c,b)||history.empty()) return;
    float target=s.GetSecondsElapsed()-1.f; auto it=std::min_element(history.begin(),history.end(),[target](const State&a,const State&d){return std::fabs(a.t-target)<std::fabs(d.t-target);});
    if(it!=history.end()) apply(*it,c,b);
}
std::filesystem::path RLTASPlugin::recordingsDir() const { return gameWrapper->GetBakkesModPath()/"data"/"RLTAS"/"Recordings"; }
void RLTASPlugin::toggleRecording(){ if(!gameWrapper->IsInFreeplay()) return; if(recordingOn){recordingOn=false;saveRecording();}else{recording.clear();recordingOn=true;lastRecording=-999.f;} }
void RLTASPlugin::cancelRecording(){ recordingOn=false;recording.clear(); }
void RLTASPlugin::saveRecording(){
    if(recording.empty()) return; std::filesystem::create_directories(recordingsDir());
    auto name=std::string("RLTAS_")+std::to_string((long long)std::time(nullptr))+".rltas"; std::ofstream f(recordingsDir()/name,std::ios::binary);
    const char magic[8]={'R','L','T','A','S','B','M','2'}; uint32_t ver=2,n=(uint32_t)recording.size(); f.write(magic,8);f.write((char*)&ver,4);f.write((char*)&n,4);
    float base=recording.front().t; for(auto &x:recording){float t=x.t-base;f.write((char*)&t,sizeof(t));f.write((char*)&x.car,sizeof(RBState));f.write((char*)&x.ball,sizeof(RBState));} recording.clear();
}
void RLTASPlugin::setGravity(int level){ gravityLevel=std::clamp(level,0,3);ServerWrapper s(0);CarWrapper c(0);BallWrapper b(0);if(freeplay(s,c,b))b.SetBallGravityScale(gravityScale(gravityLevel)); }
void RLTASPlugin::setBounce(int level){ bounceLevel=std::clamp(level,0,3);ServerWrapper s(0);CarWrapper c(0);BallWrapper b(0);if(freeplay(s,c,b))b.SetWorldBounceScale(bounceScale(bounceLevel)); }
void RLTASPlugin::resetPhysics(){ gravityLevel=bounceLevel=1;setGravity(1);setBounce(1); }
void RLTASPlugin::SetImGuiContext(uintptr_t ctx){ ImGui::SetCurrentContext(reinterpret_cast<ImGuiContext*>(ctx)); }
void RLTASPlugin::Render(){
    ImGui::TextUnformatted("RLTAS - FREEPLAY ONLY"); ImGui::Separator();
    ImGui::Text("Recording: %s",recordingOn?"ON":"OFF"); ImGui::Text("Saved state: %s",hasSaved?"READY":"EMPTY");
    ImGui::Text("Gravity: %s",levels[gravityLevel]); ImGui::Text("Ball bounciness: %s",levels[bounceLevel]);
    ImGui::Separator(); ImGui::TextUnformatted("F9 Menu | 5 Record/Save | 9 Cancel"); ImGui::TextUnformatted("R Save state | Mouse5 Restore | Mouse4 Rewind 1s");
    ImGui::TextUnformatted("6/7 Gravity -/+ | F/G Bounce +/- | 8 Reset");
    ImGui::Separator(); ImGui::TextWrapped("Recordings: %s",recordingsDir().string().c_str());
}
