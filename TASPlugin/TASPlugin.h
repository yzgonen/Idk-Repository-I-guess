#pragma once

#include "bakkesmod/plugin/bakkesmodplugin.h"
#include "bakkesmod/plugin/PluginSettingsWindow.h"
#include "bakkesmod/wrappers/wrapperstructs.h"
#include <mutex>
#include <filesystem>
#include <cstdint>
#include <atomic>
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
    // Alongside controller input, retain the live ball state for this tick.
    // Freeplay bindings from other plugins mutate the ball directly and do not
    // pass through SetVehicleInput, so input-only TAS recordings miss them.
    struct Frame { ControllerInput input{}; ActorState ball{}; bool hasBallState = false; };

private:
    enum class Mode { Idle, Recording, Playing, AtEndpoint };
    enum class PinchKind { None, Unknown, Wall, Ground, Ceiling };
    mutable std::mutex mutex_;
    Mode mode_ = Mode::Idle;
    std::vector<Frame> frames_;
    size_t playhead_ = 0;
    ActorState startCar_{}, startBall_{};
    ActorState pendingCar_{}, pendingBall_{};
    bool hasStart_ = false;
    bool hasPendingStart_ = false;
    std::string status_ = "Ready";
    std::string selectedFile_;
    std::vector<std::string> tasFiles_;
    char newTasNameInput_[128] = "shot";
    char bindInput_[5][32] = {{"F7"}, {"F8"}, {"Mouse4"}, {"B"}, {"T"}};
    std::atomic_bool newTasDialogRequested_{false};
    int bindCaptureIndex_ = -1;
    bool keyWasDown_[256]{};
    float playbackSpeed_ = 1.0f;
    float playbackFrameAccumulator_ = 0.0f;
    ControllerInput playbackBaselineInput_{};
    ControllerInput playbackLastInput_{};
    ActorState playbackLastBallState_{};
    bool playbackLastBallStateValid_ = false;
    bool playbackBaselineSet_ = false;
    ControllerInput lastLiveInput_{};
    bool hasLastLiveInput_ = false;
    float gameSpeed_ = 1.0f;
    float topBinThresholdKph_ = 110.0f;
    float ballSpeedMultiplier_ = 1.0f;
    Vector previousBallVelocity_{};
    float previousBallTouchTime_ = 0.0f;
    float previousBallWorldHitTime_ = 0.0f;
    bool hasBallPhysicsHistory_ = false;
    PinchKind lastPinchKind_ = PinchKind::None;
    float lastPinchSpeedKph_ = 0.0f;

    bool IsOffline() const;
    bool CaptureState(ActorState& car, ActorState& ball) const;
    void RestoreStart();
    void OnSetVehicleInput(void* params);
    void ApplyBallOptions();
    void RegisterCommands();
    void RequestNewShotDialog();
    void NewShot(const std::string& name);
    void RecordFromStart();
    bool CloseCurrentShot();
    void StartOrReplay();
    void ToggleBallSpeed();
    void CaptureBindInput();
    bool SaveFile(const std::string& name);
    void LoadFile(const std::string& name);
    void RefreshFileList();
    void SetBind(int index, const std::string& key);
    static std::string KeyNameFromVirtualKey(int virtualKey);
    bool IsSafeFileName(const std::string& name) const;
    std::filesystem::path DataPath(const std::string& name) const;
    void SetStatus(const std::string& message);
};
