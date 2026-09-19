#pragma once
#include <deque>
#include <chrono>
namespace RLTAS {
struct Snapshot { FReplicatedRBState car{}; FReplicatedRBState ball{}; double t{}; bool valid{}; };
class Core {
public:
 bool enabled=true, recording=false; int gravityLevel=0, bounceLevel=0; Snapshot saved{}; std::deque<Snapshot> history;
 static double Now(){ using namespace std::chrono; return duration<double>(steady_clock::now().time_since_epoch()).count(); }
 static float GravityScale(int l){ switch(l){case -1:return .5f;case 1:return 1.5f;case 2:return 2.f;default:return 1.f;} }
 static float BounceScale(int l){ switch(l){case -2:return .25f;case -1:return .5f;case 1:return 1.5f;case 2:return 2.f;default:return 1.f;} }
 bool GetActors(APlayerController_TA*& pc, ACar_TA*& car, ABall_TA*& ball, AGameEvent_Soccar_TA*& game){
  pc=static_cast<APlayerController_TA*>(USeqAct_GetEffectIntensity_TA::GetPrimaryPlayerController());
  if(!pc||!pc->IsFreeplaySessionOwner()) return false; car=pc->Car; game=static_cast<AGameEvent_Soccar_TA*>(pc->GetGameEvent());
  if(!car||!game||game->GameBalls.empty()) return false; ball=game->GameBalls[0]; return ball!=nullptr;
 }
 void Tick(){ if(!enabled)return; APlayerController_TA*pc{};ACar_TA*car{};ABall_TA*ball{};AGameEvent_Soccar_TA*game{}; if(!GetActors(pc,car,ball,game)){history.clear();return;} double now=Now(); history.push_back({car->GetCurrentRBState(),ball->GetCurrentRBState(),now,true}); while(!history.empty()&&now-history.front().t>1.25)history.pop_front(); }
 void SaveNow(){ APlayerController_TA*pc{};ACar_TA*car{};ABall_TA*ball{};AGameEvent_Soccar_TA*game{}; if(GetActors(pc,car,ball,game))saved={car->GetCurrentRBState(),ball->GetCurrentRBState(),Now(),true}; }
 void Restore(const Snapshot&s){ if(!s.valid)return; APlayerController_TA*pc{};ACar_TA*car{};ABall_TA*ball{};AGameEvent_Soccar_TA*game{}; if(!GetActors(pc,car,ball,game))return; car->SetPhysicsState(s.car);ball->SetPhysicsState(s.ball); }
 void RestoreSaved(){Restore(saved);} void RewindOneSecond(){if(history.empty())return;double target=Now()-1.;const Snapshot*best=&history.front();for(const auto&s:history)if(std::abs(s.t-target)<std::abs(best->t-target))best=&s;Restore(*best);}
 void ApplyPhysics(){APlayerController_TA*pc{};ACar_TA*car{};ABall_TA*ball{};AGameEvent_Soccar_TA*game{};if(!GetActors(pc,car,ball,game))return;ball->SetBallGravityScale(GravityScale(gravityLevel));ball->SetWorldBounceScale(BounceScale(bounceLevel));}
 void ResetPhysics(){gravityLevel=0;bounceLevel=0;ApplyPhysics();}
};}
