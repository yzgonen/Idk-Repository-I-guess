#pragma once

#include "bakkesmod/plugin/bakkesmodplugin.h"
#include "bakkesmod/plugin/PluginSettingsWindow.h"
#include "bakkesmod/wrappers/wrapperstructs.h"
#include <mutex>
#include <filesystem>
#include <string>
#include <vector>

class TASPlugin final : public BakkesMod::Plugin::BakkesModPlugin,
                        public BakkesMod::Plugin::PluginSettingsWindow {
public:
    void onLoad() override;
    void onUnload() override;
    void RenderSettings() override;
    std::string GetPluginName() override;
    void SetImGuiContext(uintptr_t ctx) override;

public:
    struct ActorState {
        Vector location{};
        Rotator rotation{};
        Vector velocity{};
        Vector angularVelocity{};
    };
    struct Frame { ControllerInput input{}; };

private:
    enum class Mode { Idle, Recording, Playing };
    mutable std::mutex mutex_;
    Mode mode_ = Mode::Idle;
    std::vector<Frame> frames_;
    size_t playhead_ = 0;
    ActorState startCar_{}, startBall_{};
    ActorState pendingCar_{}, pendingBall_{};
    bool hasStart_ = false;
    bool hasPendingStart_ = false;
    std::string status_ = "Ready";
    std::string selectedFile_ = "shot.tas";
    char fileInput_[128] = "shot.tas";
    char bindInput_[8][32] = {{"F7"}, {"F8"}, {"Mouse4"}, {"Mouse5"}, {"="}, {"B"}, {"T"}, {"Z"}};
    int bindCaptureIndex_ = -1;
    bool keyWasDown_[256]{};
    float playbackSpeed_ = 1.0f;
    float playbackFrameAccumulator_ = 0.0f;
    float gameSpeed_ = 1.0f;
    bool topBinAim_ = false;
    float ballSpeedMultiplier_ = 1.0f;
    Vector previousBallVelocity_{};

    bool IsOffline() const;
    bool CaptureState(ActorState& car, ActorState& ball) const;
    void RestoreStart();
    void OnPlayerMove();
    void ApplyPlaybackInput();
    void RegisterCommands();
    void NewShot();
    void StartOrReplay();
    void Stop();
    void UpdateStartPoint();
    void Undo();
    void CyclePlaybackSpeed();
    void ToggleBallSpeed();
    void ToggleTopBinAim();
    void CaptureBindInput();
    void SaveFile(const std::string& name);
    void LoadFile(const std::string& name);
    void SetBind(int index, const std::string& key);
    static std::string KeyNameFromVirtualKey(int virtualKey);
    bool IsSafeFileName(const std::string& name) const;
    std::filesystem::path DataPath(const std::string& name) const;
    void SetStatus(const std::string& message);
};
