#pragma once
#pragma comment(lib, "pluginsdk.lib")
#include "bakkesmod/plugin/bakkesmodplugin.h"
#include "bakkesmod/plugin/pluginwindow.h"
#include "bakkesmod/wrappers/GameEvent/ServerWrapper.h"
#include "bakkesmod/wrappers/GameObject/CarWrapper.h"
#include "bakkesmod/wrappers/GameObject/BallWrapper.h"
#include <deque>
#include <vector>
#include <filesystem>

class RLTASPlugin final : public BakkesMod::Plugin::BakkesModPlugin, public BakkesMod::Plugin::PluginWindow {
public:
    void onLoad() override;
    void onUnload() override;
    void Render() override;
    std::string GetMenuName() override { return "rltas"; }
    std::string GetMenuTitle() override { return "RL TAS"; }
    void SetImGuiContext(uintptr_t ctx) override;
    bool ShouldBlockInput() override { return true; }
    bool IsActiveOverlay() override { return false; }
    void OnOpen() override { menuOpen = true; }
    void OnClose() override { menuOpen = false; }

private:
    struct State { RBState car{}, ball{}; float t = 0.f; };
    std::deque<State> history;
    std::vector<State> recording;
    State saved{};
    bool hasSaved=false, recordingOn=false, menuOpen=false;
    int gravityLevel=1, bounceLevel=1;
    float lastHistory=-999.f, lastRecording=-999.f;

    bool freeplay(ServerWrapper& s, CarWrapper& c, BallWrapper& b);
    State capture(ServerWrapper s, CarWrapper c, BallWrapper b);
    void apply(const State& st, CarWrapper c, BallWrapper b);
    void tick(std::string);
    void savePoint();
    void restorePoint();
    void rewindOneSecond();
    void toggleRecording();
    void cancelRecording();
    void saveRecording();
    void setGravity(int level);
    void setBounce(int level);
    void resetPhysics();
    void bindKeys();
    std::filesystem::path recordingsDir() const;
};
