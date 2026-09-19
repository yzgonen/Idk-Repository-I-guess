#pragma once
#include <deque>
#include <vector>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <cstdlib>

namespace RLTAS {
struct Snapshot { FReplicatedRBState car{}; FReplicatedRBState ball{}; double t{}; bool valid{}; };

class Core {
public:
 bool enabled=true, recording=false;
 int gravityLevel=0, bounceLevel=0;
 Snapshot saved{};
 std::deque<Snapshot> history;
 std::vector<Snapshot> recordingFrames;
 double recordingStart=0.0;
 std::filesystem::path lastRecordingPath{};

 static double Now(){ using namespace std::chrono; return duration<double>(steady_clock::now().time_since_epoch()).count(); }

 static std::filesystem::path RecordingDirectory(){
  char* profile=nullptr; size_t len=0;
  if(_dupenv_s(&profile,&len,"USERPROFILE")==0 && profile){
   std::filesystem::path dir=std::filesystem::path(profile)/"Documents"/"RLTAS"/"Recordings";
   free(profile); return dir;
  }
  if(profile) free(profile);
  return std::filesystem::current_path()/"RLTAS"/"Recordings";
 }

 static float GravityScale(int l){ switch(l){case -1:return .5f;case 1:return 2.f;case 2:return 3.f;default:return 1.f;} }
 static float BounceScale(int l){ switch(l){case -1:return .5f;case 1:return 1.5f;case 2:return 2.f;default:return 1.f;} }

 bool GetActors(APlayerController_TA*& pc, ACar_TA*& car, ABall_TA*& ball, AGameEvent_Soccar_TA*& game){
  pc=static_cast<APlayerController_TA*>(USeqAct_GetEffectIntensity_TA::GetPrimaryPlayerController());
  if(!pc||!pc->IsFreeplaySessionOwner()) return false;
  car=pc->Car;
  game=static_cast<AGameEvent_Soccar_TA*>(pc->GetGameEvent());
  if(!car||!game||game->GameBalls.empty()) return false;
  ball=game->GameBalls[0];
  return ball!=nullptr;
 }

 Snapshot Capture(){
  APlayerController_TA*pc{}; ACar_TA*car{}; ABall_TA*ball{}; AGameEvent_Soccar_TA*game{};
  if(!GetActors(pc,car,ball,game)) return {};
  return {car->GetCurrentRBState(),ball->GetCurrentRBState(),Now(),true};
 }

 void Tick(){
  if(!enabled)return;
  Snapshot s=Capture();
  if(!s.valid){history.clear(); if(recording) CancelRecording(); return;}
  history.push_back(s);
  while(!history.empty()&&s.t-history.front().t>1.25)history.pop_front();
  if(recording) recordingFrames.push_back(s);
 }

 void SaveNow(){ Snapshot s=Capture(); if(s.valid)saved=s; }

 void Restore(const Snapshot&s){
  if(!s.valid)return;
  APlayerController_TA*pc{};ACar_TA*car{};ABall_TA*ball{};AGameEvent_Soccar_TA*game{};
  if(!GetActors(pc,car,ball,game))return;
  car->SetPhysicsState(s.car); ball->SetPhysicsState(s.ball);
 }

 void RestoreSaved(){Restore(saved);}

 void RewindOneSecond(){
  if(history.empty())return;
  double target=Now()-1.;
  const Snapshot*best=&history.front();
  for(const auto&s:history)if(std::abs(s.t-target)<std::abs(best->t-target))best=&s;
  Restore(*best);
 }

 void StartRecording(){
  if(recording)return;
  recordingFrames.clear();
  recordingStart=Now();
  recording=true;
 }

 bool StopAndSaveRecording(){
  if(!recording)return false;
  recording=false;
  if(recordingFrames.empty())return false;
  std::error_code ec;
  auto dir=RecordingDirectory();
  std::filesystem::create_directories(dir,ec);
  if(ec)return false;
  auto now=std::chrono::system_clock::now();
  std::time_t tt=std::chrono::system_clock::to_time_t(now);
  std::tm tm{};
  localtime_s(&tm,&tt);
  std::ostringstream name;
  name<<"RLTAS_"<<std::put_time(&tm,"%Y%m%d_%H%M%S")<<".rltas";
  auto outPath=dir/name.str();
  std::ofstream out(outPath,std::ios::binary);
  if(!out)return false;
  const char magic[8]={'R','L','T','A','S','0','0','1'};
  out.write(magic,sizeof(magic));
  const uint32_t version=1;
  const uint64_t count=static_cast<uint64_t>(recordingFrames.size());
  out.write(reinterpret_cast<const char*>(&version),sizeof(version));
  out.write(reinterpret_cast<const char*>(&count),sizeof(count));
  const double base=recordingFrames.front().t;
  for(const auto& frame:recordingFrames){
   const double relative=frame.t-base;
   out.write(reinterpret_cast<const char*>(&relative),sizeof(relative));
   out.write(reinterpret_cast<const char*>(&frame.car),sizeof(frame.car));
   out.write(reinterpret_cast<const char*>(&frame.ball),sizeof(frame.ball));
  }
  if(!out.good())return false;
  out.close();
  lastRecordingPath=outPath;
  recordingFrames.clear();
  return true;
 }

 void CancelRecording(){
  recording=false;
  recordingFrames.clear();
  recordingStart=0.0;
 }

 void ToggleRecording(){ if(recording) StopAndSaveRecording(); else StartRecording(); }

 void ApplyPhysics(){
  APlayerController_TA*pc{};ACar_TA*car{};ABall_TA*ball{};AGameEvent_Soccar_TA*game{};
  if(!GetActors(pc,car,ball,game))return;
  ball->SetBallGravityScale(GravityScale(gravityLevel));
  ball->SetWorldBounceScale(BounceScale(bounceLevel));
 }
 void ResetPhysics(){gravityLevel=0;bounceLevel=0;ApplyPhysics();}
};
}
