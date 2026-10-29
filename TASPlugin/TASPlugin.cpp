#include "TASPlugin.h"
#include "bakkesmod/wrappers/includes.h"
#include "imgui/imgui.h"
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <limits>
#include <iterator>
#include <sstream>
#include <Windows.h>

BAKKESMOD_PLUGIN(TASPlugin, "TAS Plugin", "2.0.0", PLUGINTYPE_FREEPLAY)

namespace {
constexpr const char* kInputEvent = "Function TAGame.Car_TA.SetVehicleInput";
constexpr int kBindCount = 8;
constexpr int kBindCvarIndices[kBindCount] = {0, 1, 2, 3, 5, 6, 7, 8};
constexpr const char* kBindCommands[kBindCount] = {
    "tasplugin_new", "tasplugin_play", "tasplugin_save",
    "tasplugin_toggle_ball_speed", "tasplugin_record", "tasplugin_update_start",
    "tasplugin_play_complete", "tasplugin_delete"
};
constexpr const char* kBindLabels[] = {
    "New TAS", "Play The Shot", "Save",
    "Toggle ball speed (1x / 2x)", "Record from start", "Update start point",
    "Play Complete Shot", "Delete Current TAS"
};
constexpr const char* kGoalScoredEvent = "Function TAGame.Ball_TA.OnHitGoal";

float Length(const Vector& v) { return std::sqrt(v.X * v.X + v.Y * v.Y + v.Z * v.Z); }
Vector Scale(const Vector& v, float s) { return Vector{v.X * s, v.Y * s, v.Z * s}; }
Vector Subtract(const Vector& a, const Vector& b) { return Vector{a.X-b.X, a.Y-b.Y, a.Z-b.Z}; }
Vector Add(const Vector& a, const Vector& b) { return Vector{a.X+b.X, a.Y+b.Y, a.Z+b.Z}; }
float Dot(const Vector& a, const Vector& b) { return a.X*b.X + a.Y*b.Y + a.Z*b.Z; }
Vector Normalize(const Vector& v) {
    const float length = Length(v);
    return length > 0.001f ? Scale(v, 1.0f / length) : Vector{};
}
Vector Cross(const Vector& a, const Vector& b) {
    return Vector{a.Y*b.Z - a.Z*b.Y, a.Z*b.X - a.X*b.Z, a.X*b.Y - a.Y*b.X};
}
float ProjectedAabbExtent(const Vector& axis, const Vector& extent) {
    return std::fabs(axis.X) * extent.X + std::fabs(axis.Y) * extent.Y + std::fabs(axis.Z) * extent.Z;
}

bool InputsDiffer(const ControllerInput& a, const ControllerInput& b) {
    constexpr float deadzone = 0.05f;
    return std::fabs(a.Throttle - b.Throttle) > deadzone || std::fabs(a.Steer - b.Steer) > deadzone ||
        std::fabs(a.Pitch - b.Pitch) > deadzone || std::fabs(a.Yaw - b.Yaw) > deadzone ||
        std::fabs(a.Roll - b.Roll) > deadzone || std::fabs(a.DodgeForward - b.DodgeForward) > deadzone ||
        std::fabs(a.DodgeStrafe - b.DodgeStrafe) > deadzone || a.Handbrake != b.Handbrake ||
        a.Jump != b.Jump || a.ActivateBoost != b.ActivateBoost || a.HoldingBoost != b.HoldingBoost ||
        a.Jumped != b.Jumped;
}

bool IsBallStateJump(const TASPlugin::ActorState& previous, const TASPlugin::ActorState& current) {
    // Ordinary frame-to-frame physics moves the ball by tens of Unreal units.
    // These wider limits catch Free Play reset/launch actions without treating
    // every normal physics tick as a teleport.
    return Length(Subtract(current.location, previous.location)) > 300.0f ||
        Length(Subtract(current.velocity, previous.velocity)) > 1800.0f;
}

void WriteActor(std::ostream& out, const TASPlugin::ActorState& a) {
    out << a.location.X << ',' << a.location.Y << ',' << a.location.Z << ','
        << a.rotation.Pitch << ',' << a.rotation.Yaw << ',' << a.rotation.Roll << ','
        << a.velocity.X << ',' << a.velocity.Y << ',' << a.velocity.Z << ','
        << a.angularVelocity.X << ',' << a.angularVelocity.Y << ',' << a.angularVelocity.Z;
}

bool ReadActor(std::istream& in, TASPlugin::ActorState& a) {
    char comma = 0;
    return static_cast<bool>(in >> a.location.X >> comma >> a.location.Y >> comma >> a.location.Z >> comma
        >> a.rotation.Pitch >> comma >> a.rotation.Yaw >> comma >> a.rotation.Roll >> comma
        >> a.velocity.X >> comma >> a.velocity.Y >> comma >> a.velocity.Z >> comma
        >> a.angularVelocity.X >> comma >> a.angularVelocity.Y >> comma >> a.angularVelocity.Z);
}
}

void TASPlugin::onLoad() {
    cvarManager->registerCvar("tasplugin_gamespeed", "1.0", "Rocket League game speed", true, true, 0.5f, true, 2.0f);
    cvarManager->registerCvar("tasplugin_ball_speed", "1.0", "Ball velocity multiplier", true, true, 1.0f, true, 2.0f);
    cvarManager->registerCvar("tasplugin_topbin_min_kph", "110", "Top-bin aim minimum ball speed in km/h", true, true, 0.0f, true, 300.0f);
    gameSpeed_ = cvarManager->getCvar("tasplugin_gamespeed").getFloatValue();
    ballSpeedMultiplier_ = cvarManager->getCvar("tasplugin_ball_speed").getFloatValue();
    topBinThresholdKph_ = cvarManager->getCvar("tasplugin_topbin_min_kph").getFloatValue();
    RegisterCommands();
    const char* defaultBinds[] = {"F7", "F8", "Mouse4", "B", "F9", "F10", "F11", "F12"};
    for (int i = 0; i < kBindCount; ++i) {
        const std::string cvar = "tasplugin_named_bind_" + std::to_string(kBindCvarIndices[i]);
        cvarManager->registerCvar(cvar, defaultBinds[i], "TAS Plugin bind");
        const std::string key = cvarManager->getCvar(cvar).getStringValue();
        std::strncpy(bindInput_[i], key.c_str(), sizeof(bindInput_[i]) - 1);
        bindInput_[i][sizeof(bindInput_[i]) - 1] = '\0';
        cvarManager->executeCommand("bind " + key + " \"" + kBindCommands[i] + "\"", false);
    }
    gameWrapper->HookEventWithCaller<CarWrapper>(kInputEvent,
        [this](CarWrapper, void* params, std::string) { OnSetVehicleInput(params); });
    gameWrapper->HookEvent(kGoalScoredEvent, [this](std::string) { OnGoalScored(); });
    RefreshFileList();
    SetStatus("Ready — create or select a TAS in offline Free Play.");
}

void TASPlugin::onUnload() {
    gameWrapper->UnhookEvent(kInputEvent);
    gameWrapper->UnhookEvent(kGoalScoredEvent);
    CloseCurrentShot();
}

std::string TASPlugin::GetPluginName() { return "TASPlugin"; }

void TASPlugin::SetImGuiContext(uintptr_t ctx) {
    ImGui::SetCurrentContext(reinterpret_cast<ImGuiContext*>(ctx));
}

bool TASPlugin::IsOffline() const {
    return gameWrapper->IsInFreeplay() || gameWrapper->IsInCustomTraining();
}

bool TASPlugin::CaptureState(ActorState& carState, ActorState& ballState) const {
    if (!IsOffline()) return false;
    CarWrapper car = gameWrapper->GetLocalCar();
    BallWrapper ball = gameWrapper->GetCurrentGameState().GetBall();
    if (car.IsNull() || ball.IsNull()) return false;
    carState = {car.GetLocation(), car.GetRotation(), car.GetVelocity(), car.GetAngularVelocity()};
    ballState = {ball.GetLocation(), ball.GetRotation(), ball.GetVelocity(), ball.GetAngularVelocity()};
    return true;
}

void TASPlugin::RestoreStart() {
    ActorState carState, ballState;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (hasPendingStart_) {
            startCar_ = pendingCar_;
            startBall_ = pendingBall_;
            hasStart_ = true;
            hasPendingStart_ = false;
        }
        if (!hasStart_) return;
        carState = startCar_;
        ballState = startBall_;
    }
    CarWrapper car = gameWrapper->GetLocalCar();
    BallWrapper ball = gameWrapper->GetCurrentGameState().GetBall();
    if (car.IsNull() || ball.IsNull()) return;
    car.SetLocation(carState.location);
    car.SetRotation(carState.rotation);
    car.SetVelocity(carState.velocity);
    car.SetAngularVelocity(carState.angularVelocity, false);
    ball.SetLocation(ballState.location);
    ball.SetRotation(ballState.rotation);
    ball.SetVelocity(ballState.velocity);
    ball.SetAngularVelocity(ballState.angularVelocity, false);
    previousBallVelocity_ = ballState.velocity;
}

void TASPlugin::RegisterCommands() {
    cvarManager->registerNotifier("tasplugin_new", [this](std::vector<std::string>) { RequestNewShotDialog(); }, "Create and name a new TAS", PERMISSION_ALL);
    cvarManager->registerNotifier("tasplugin_play", [this](std::vector<std::string>) { StartOrReplay(); }, "Replay the loaded TAS from the beginning", PERMISSION_ALL);
    cvarManager->registerNotifier("tasplugin_save", [this](std::vector<std::string>) { SaveCurrentTas(); }, "Save the current TAS without closing it", PERMISSION_ALL);
    cvarManager->registerNotifier("tasplugin_toggle_ball_speed", [this](std::vector<std::string>) { ToggleBallSpeed(); }, "Toggle ball speed between 1x and 2x", PERMISSION_ALL);
    cvarManager->registerNotifier("tasplugin_record", [this](std::vector<std::string>) { RecordFromStart(); }, "Record a new take from the selected TAS start", PERMISSION_ALL);
    cvarManager->registerNotifier("tasplugin_update_start", [this](std::vector<std::string>) { UpdateStartPoint(); }, "Move the TAS start point to the current playback position", PERMISSION_ALL);
    cvarManager->registerNotifier("tasplugin_play_complete", [this](std::vector<std::string>) { PlayCompleteShot(); }, "Play the full saved TAS without branching", PERMISSION_ALL);
    cvarManager->registerNotifier("tasplugin_delete", [this](std::vector<std::string>) { DeleteCurrentTas(); }, "Delete the selected TAS file", PERMISSION_ALL);
}

void TASPlugin::RequestNewShotDialog() {
    newTasDialogRequested_.store(true);
}

void TASPlugin::NewShot(const std::string& name) {
    std::string canonicalName = name;
    if (std::filesystem::path(canonicalName).extension() == ".tas")
        canonicalName = std::filesystem::path(canonicalName).stem().string();
    if (!IsSafeFileName(canonicalName)) { SetStatus("Choose a simple TAS name using letters, numbers, _ or -."); return; }
    if (!IsOffline()) { SetStatus("TAS recording works only in Free Play or Custom Training."); return; }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (std::find(tasFiles_.begin(), tasFiles_.end(), canonicalName) != tasFiles_.end()) {
            status_ = "That name already exists. Select that TAS or choose another name.";
            return;
        }
    }
    // Use Rocket League's own Free Play reset routines so every new shot begins
    // from the game's standard spawn state instead of wherever the player was.
    if (gameWrapper->IsInFreeplay()) {
        ServerWrapper state = gameWrapper->GetCurrentGameState();
        if (!state.IsNull()) {
            state.ResetPlayers();
            state.ResetBalls();
        }
    }
    ActorState car, ball;
    if (!CaptureState(car, ball)) { SetStatus("Could not read the current car and ball state."); return; }
    if (!CloseCurrentShot()) return;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        frames_.clear();
        startCar_ = car;
        startBall_ = ball;
        hasStart_ = true;
        hasPendingStart_ = false;
        startFrameIndex_ = 0;
        completePlayback_ = false;
        playhead_ = 0;
        playbackFrameAccumulator_ = 0.0f;
        previousBallVelocity_ = ball.velocity;
        mode_ = Mode::Idle;
        selectedFile_ = canonicalName;
    }
    // Write an empty shot immediately so it appears in the selector and remains
    // available even before the user records any inputs.
    if (!SaveFile(canonicalName)) return;
    RefreshFileList();
    SetStatus("Created and selected " + canonicalName + ". Use Record from start to record, or Play The Shot to replay it.");
}

void TASPlugin::RecordFromStart() {
    if (!IsOffline()) { SetStatus("TAS recording works only in Free Play or Custom Training."); return; }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (selectedFile_.empty() || !hasStart_) {
            status_ = "Create or select a TAS before recording.";
            return;
        }
        if (startFrameIndex_ > frames_.size()) startFrameIndex_ = frames_.size();
        frames_.resize(startFrameIndex_); // Keep recorded history before the selected start point.
        playhead_ = startFrameIndex_;
        playbackFrameAccumulator_ = 0.0f;
        lastRecordedBallState_ = startBall_;
        hasLastRecordedBallState_ = hasStart_;
        mode_ = Mode::Recording;
        completePlayback_ = false;
        status_ = "Recording from the saved start. Save to keep this take; Play The Shot reloads the last saved take.";
    }
    RestoreStart();
}

void TASPlugin::StartOrReplay() {
    // Ordinary Play The Shot remains interactive: taking control branches it.
    PlayCompleteShot();
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (mode_ == Mode::Playing) {
            completePlayback_ = false;
            status_ = "Playing The Shot from the saved start. Take control to branch the shot.";
        }
    }
}

void TASPlugin::PlayCompleteShot() {
    if (!IsOffline()) { SetStatus("TAS playback works only in Free Play or Custom Training."); return; }
    std::string savedName;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        savedName = selectedFile_;
    }
    if (savedName.empty()) { SetStatus("Create or select a TAS before playback."); return; }
    // Play always means replay the last explicitly saved take. Unsaved recording
    // changes are discarded here, leaving the previous saved shot intact.
    LoadFile(savedName);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (frames_.empty() || startFrameIndex_ >= frames_.size()) { status_ = "There are no saved frames after this start point. Record a take first."; return; }
        if (hasPendingStart_) {
            startCar_ = pendingCar_;
            startBall_ = pendingBall_;
            hasStart_ = true;
            hasPendingStart_ = false;
        }
        if (!hasStart_) { status_ = "This TAS has no start point. Create a new TAS first."; return; }
        mode_ = Mode::Playing;
        playhead_ = startFrameIndex_;
        playbackFrameAccumulator_ = 0.0f;
        playbackBaselineSet_ = false;
        playbackLastInput_ = frames_[playhead_].input;
        completePlayback_ = true;
    }
    RestoreStart();
    SetStatus("Playing the complete saved shot from its start point.");
}

void TASPlugin::UpdateStartPoint() {
    if (!IsOffline()) { SetStatus("Update start point works only in offline Free Play or Custom Training."); return; }
    ActorState car, ball;
    if (!CaptureState(car, ball)) { SetStatus("Could not read the current car and ball state."); return; }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (mode_ != Mode::Playing) { status_ = "Play a saved TAS, then update the start point while it is playing."; return; }
        startCar_ = car;
        startBall_ = ball;
        startFrameIndex_ = std::min(playhead_, frames_.size());
        hasStart_ = true;
        hasPendingStart_ = false;
        status_ = "Start point moved to this moment. Earlier recorded frames are kept.";
    }
    SaveCurrentTas();
}

void TASPlugin::DeleteCurrentTas() {
    std::string name;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        name = selectedFile_;
    }
    if (name.empty() || !IsSafeFileName(name)) { SetStatus("Select a TAS before deleting it."); return; }
    std::error_code ec;
    const bool removed = std::filesystem::remove(DataPath(name), ec);
    if (ec || !removed) { SetStatus("Could not delete " + name + "."); return; }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        mode_ = Mode::Idle;
        frames_.clear(); playhead_ = 0; startFrameIndex_ = 0;
        hasStart_ = false; hasPendingStart_ = false; completePlayback_ = false;
        selectedFile_.clear();
    }
    RefreshFileList();
    SetStatus("Deleted " + name + ".");
}

void TASPlugin::OnGoalScored() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (mode_ != Mode::Playing) return;
    mode_ = Mode::Idle;
    completePlayback_ = false;
    playbackFrameAccumulator_ = 0.0f;
    status_ = "Goal scored. TAS playback stopped for the goal replay.";
}

bool TASPlugin::CloseCurrentShot() {
    std::string name;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        name = selectedFile_;
        mode_ = Mode::Idle;
        frames_.clear();
        playhead_ = 0;
        playbackFrameAccumulator_ = 0.0f;
        hasStart_ = false;
        hasPendingStart_ = false;
        hasLastRecordedBallState_ = false;
        selectedFile_.clear();
    }
    RefreshFileList();
    SetStatus(name.empty() ? "No TAS was open." : "Closed TAS without saving: " + name + ".");
    return true;
}

void TASPlugin::ToggleBallSpeed() {
    float multiplier;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        ballSpeedMultiplier_ = ballSpeedMultiplier_ > 1.5f ? 1.0f : 2.0f;
        multiplier = ballSpeedMultiplier_;
        status_ = multiplier > 1.5f ? "Ball speed set to 2x." : "Ball speed set to 1x.";
    }
    cvarManager->getCvar("tasplugin_ball_speed").setValue(multiplier);
    cvarManager->executeCommand("writeconfig", false);
}

void TASPlugin::SaveCurrentTas() {
    std::string name;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        name = selectedFile_;
    }
    if (name.empty()) { SetStatus("Create or select a TAS before saving."); return; }
    SaveFile(name);
}

void TASPlugin::OnSetVehicleInput(void* params) {
    if (params == nullptr) return;
    auto* input = static_cast<ControllerInput*>(params);
    if (!IsOffline()) return;

    // Snapshot the ball on the same input tick. This also captures direct
    // Freeplay actions (for example reset/launch bindings) that bypass the
    // controller input structure entirely.
    ActorState liveBallState{};
    bool hasLiveBallState = false;
    BallWrapper liveBall = gameWrapper->GetCurrentGameState().GetBall();
    if (!liveBall.IsNull()) {
        liveBallState = {liveBall.GetLocation(), liveBall.GetRotation(), liveBall.GetVelocity(), liveBall.GetAngularVelocity()};
        hasLiveBallState = true;
    }

    ActorState ballStateToRestore{};
    bool restoreBallState = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        const ControllerInput liveInput = *input;
        auto appendRecordedFrame = [&]() {
            const bool isBallKeyframe = hasLiveBallState &&
                (!hasLastRecordedBallState_ || IsBallStateJump(lastRecordedBallState_, liveBallState));
            frames_.push_back({liveInput, liveBallState, isBallKeyframe});
            if (hasLiveBallState) {
                lastRecordedBallState_ = liveBallState;
                hasLastRecordedBallState_ = true;
            }
        };
        if (mode_ == Mode::Recording) {
            appendRecordedFrame();
        } else if (mode_ == Mode::AtEndpoint) {
            if (!completePlayback_ && InputsDiffer(liveInput, playbackBaselineInput_)) {
                mode_ = Mode::Recording;
                hasLastRecordedBallState_ = false;
                appendRecordedFrame();
                status_ = "Control resumed at the endpoint. Recording your continuation.";
            }
        } else if (mode_ == Mode::Playing) {
            if (!playbackBaselineSet_) {
                playbackBaselineInput_ = hasLastLiveInput_ ? lastLiveInput_ : liveInput;
                playbackBaselineSet_ = true;
            }
            if (InputsDiffer(liveInput, playbackBaselineInput_)) {
                if (playhead_ < frames_.size()) frames_.resize(playhead_);
                mode_ = Mode::Recording;
                hasLastRecordedBallState_ = false;
                appendRecordedFrame();
                status_ = "Control taken. The unused future was removed; recording this new route.";
            }

            if (mode_ == Mode::Playing) {
                if (frames_.empty()) {
                    mode_ = Mode::Idle;
                    status_ = "This TAS has no recorded inputs yet.";
                } else {
                    playbackFrameAccumulator_ += playbackSpeed_;
                    const size_t steps = static_cast<size_t>(playbackFrameAccumulator_);
                    if (steps > 0) {
                        playbackFrameAccumulator_ -= static_cast<float>(steps);
                        const size_t lastFrame = std::min(frames_.size() - 1, playhead_ + steps - 1);
                        playbackLastInput_ = frames_[lastFrame].input;
                        for (size_t frameIndex = playhead_; frameIndex <= lastFrame; ++frameIndex) {
                            if (frames_[frameIndex].hasBallState) {
                                ballStateToRestore = frames_[frameIndex].ball;
                                restoreBallState = true;
                            }
                        }
                        *input = playbackLastInput_;
                        playhead_ = lastFrame + 1;
                        if (playhead_ >= frames_.size()) {
                            if (completePlayback_) {
                                mode_ = Mode::Idle;
                                completePlayback_ = false;
                                status_ = "Complete shot playback finished.";
                            } else {
                                mode_ = Mode::AtEndpoint;
                                status_ = "Reached the saved endpoint. Control is yours; move to record a continuation.";
                            }
                        }
                    } else {
                        *input = playbackLastInput_;
                    }
                }
            }
        }
        lastLiveInput_ = liveInput;
        hasLastLiveInput_ = true;
    }
    if (restoreBallState && !liveBall.IsNull()) {
        liveBall.SetLocation(ballStateToRestore.location);
        liveBall.SetRotation(ballStateToRestore.rotation);
        liveBall.SetVelocity(ballStateToRestore.velocity);
        liveBall.SetAngularVelocity(ballStateToRestore.angularVelocity, false);
    }
    ApplyBallOptions();
}

void TASPlugin::ApplyBallOptions() {
    BallWrapper ball = gameWrapper->GetCurrentGameState().GetBall();
    if (ball.IsNull()) return;
    Vector velocity = ball.GetVelocity();
    const Vector ballLocation = ball.GetLocation();
    const float touchTime = ball.GetLastTouchTime();
    const float worldHitTime = ball.GetLastHitWorldTime();
    if (hasBallPhysicsHistory_) {
        const Vector impulseDelta = Subtract(velocity, previousBallVelocity_);
        const float impulseSize = Length(impulseDelta);
        const bool newCarTouch = touchTime > previousBallTouchTime_ + 0.001f;
        const bool newWorldHit = worldHitTime > previousBallWorldHitTime_ + 0.001f;
        const bool sameMoment = std::fabs(touchTime - worldHitTime) <= 0.15f;
        CarWrapper car = gameWrapper->GetLocalCar();
        const bool carWasClose = !car.IsNull() && Length(Subtract(car.GetLocation(), ballLocation)) <= ball.GetRadius() + 280.0f;
        const float incomingSpeedKph = Length(velocity) * 0.036f;
        if (newCarTouch && newWorldHit && sameMoment && carWasClose && impulseSize >= 800.0f && incomingSpeedKph >= 60.0f) {
            // For a surface collision, incoming minus outgoing velocity points
            // approximately along the contacted surface's outward normal.
            const Vector collisionNormal = Normalize(Subtract(previousBallVelocity_, velocity));
            PinchKind detected = PinchKind::Unknown;
            if (collisionNormal.Z <= -0.65f) detected = PinchKind::Ground;
            else if (collisionNormal.Z >= 0.65f) detected = PinchKind::Ceiling;
            else if (std::sqrt(collisionNormal.X * collisionNormal.X + collisionNormal.Y * collisionNormal.Y) >= 0.65f)
                detected = PinchKind::Wall;
            std::lock_guard<std::mutex> lock(mutex_);
            lastPinchKind_ = detected;
            lastPinchSpeedKph_ = incomingSpeedKph;
        }
    }
    float ballMultiplier = 1.0f;
    float topBinThresholdKph = 110.0f;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        ballMultiplier = ballSpeedMultiplier_;
        topBinThresholdKph = topBinThresholdKph_;
    }
    const float oldSpeed = Length(previousBallVelocity_);
    float newSpeed = Length(velocity);
    if (ballMultiplier > 1.01f && newSpeed > 1000.0f && std::fabs(newSpeed - oldSpeed) > 1000.0f) {
        velocity = Scale(velocity, ballMultiplier);
        ball.SetVelocity(velocity);
        newSpeed *= ballMultiplier;
    }
    const float speedKph = newSpeed * 0.036f;
    if (speedKph >= topBinThresholdKph) {
        const Vector loc = ballLocation;
        const float gravityScale = ball.GetReplicatedBallGravityScale();
        const Vector gravity{0.0f, 0.0f, -650.0f * gravityScale};
        ServerWrapper gameState = gameWrapper->GetCurrentGameState();
        auto goals = gameState.GetGoals();
        float nearestGoalTime = std::numeric_limits<float>::max();
        Vector aimPoint{};
        bool foundGoalPath = false;

        for (int i = 0; i < goals.Count(); ++i) {
            GoalWrapper goal = goals.Get(i);
            if (goal.IsNull()) continue;

            const Vector front = goal.GetWorldFrontCenter();
            const Vector right = Normalize(goal.GetRight());
            const Vector up = Normalize(goal.GetUp());
            Vector normal = Normalize(Cross(right, up));
            if (Length(right) < 0.9f || Length(up) < 0.9f || Length(normal) < 0.9f) continue;

            const float planeA = 0.5f * Dot(gravity, normal);
            const float planeB = Dot(velocity, normal);
            const float planeC = Dot(Subtract(loc, front), normal);
            float timeToGoal = std::numeric_limits<float>::max();
            if (std::fabs(planeA) < 0.001f) {
                if (std::fabs(planeB) < 1.0f) continue;
                timeToGoal = -planeC / planeB;
            } else {
                const float discriminant = planeB * planeB - 4.0f * planeA * planeC;
                if (discriminant < 0.0f) continue;
                const float root = std::sqrt(discriminant);
                const float first = (-planeB - root) / (2.0f * planeA);
                const float second = (-planeB + root) / (2.0f * planeA);
                if (first > 0.0f) timeToGoal = first;
                if (second > 0.0f) timeToGoal = std::min(timeToGoal, second);
            }
            if (timeToGoal <= 0.0f || timeToGoal >= nearestGoalTime || timeToGoal > 4.0f) continue;

            const Vector impact = Add(Add(loc, Scale(velocity, timeToGoal)), Scale(gravity, 0.5f * timeToGoal * timeToGoal));
            const Vector offset = Subtract(impact, front);
            const float lateral = Dot(offset, right);
            const float vertical = Dot(offset, up);

            // Goal local extents are half-sizes along its local right/up axes.
            // Fall back to projected world bounds if a map does not populate them.
            const Vector localExtent = goal.GetLocalExtent();
            const Vector worldExtent = goal.GetWorldExtent();
            float halfWidth = std::fabs(localExtent.Y);
            float halfHeight = std::fabs(localExtent.Z);
            if (halfWidth < 100.0f) halfWidth = ProjectedAabbExtent(right, worldExtent);
            if (halfHeight < 100.0f) halfHeight = ProjectedAabbExtent(up, worldExtent);
            if (halfWidth < 150.0f || halfHeight < 150.0f) continue;

            constexpr float ballRadius = 93.0f;
            const float openingHalfWidth = halfWidth - ballRadius;
            const float openingHalfHeight = halfHeight - ballRadius;
            // Only engage for a trajectory that is already reasonably close to
            // this goal. A little extra room lets the assist correct near-post
            // and crossbar misses without bending unrelated shots across goal.
            if (std::fabs(lateral) > openingHalfWidth + 300.0f ||
                std::fabs(vertical) > openingHalfHeight + 300.0f) continue;

            // Aim for the upper left or upper right corner. Keep the ball's
            // center one radius plus a small safety inset inside the visible
            // opening; the goal trigger's rectangular extent can include the
            // crossbar/post collision geometry.
            constexpr float cornerInset = ballRadius + 45.0f;
            const float side = std::fabs(lateral) > 1.0f
                ? (lateral > 0.0f ? 1.0f : -1.0f)
                : (Dot(velocity, right) >= 0.0f ? 1.0f : -1.0f);
            const float targetLateral = side * std::max(0.0f, halfWidth - cornerInset);
            const float targetVertical = std::max(0.0f, halfHeight - cornerInset);
            aimPoint = Add(front, Add(Scale(right, targetLateral), Scale(up, targetVertical)));
            nearestGoalTime = timeToGoal;
            foundGoalPath = true;
        }

        if (foundGoalPath) {
            const float flightTime = nearestGoalTime;
            Vector direction = Subtract(Subtract(aimPoint, loc), Scale(gravity, 0.5f * flightTime * flightTime));
            const float length = Length(direction);
            const float currentSpeed = Length(velocity);
            if (length > 1.0f && currentSpeed > 1.0f) {
                // Recalculate the ideal crossing point every tick, but only
                // turn a small amount per tick. This gives the pinch a gradual
                // curve instead of instantly rotating its velocity toward goal.
                const Vector currentDirection = Scale(velocity, 1.0f / currentSpeed);
                const Vector targetDirection = Scale(direction, 1.0f / length);
                const float alignment = std::clamp(Dot(currentDirection, targetDirection), -1.0f, 1.0f);
                const float angle = std::acos(alignment);
                constexpr float maxTurnPerTick = 0.004f;
                const float blend = angle > maxTurnPerTick ? maxTurnPerTick / angle : 1.0f;
                const Vector steeredDirection = Normalize(Add(Scale(currentDirection, 1.0f - blend),
                                                               Scale(targetDirection, blend)));
                velocity = Scale(steeredDirection, currentSpeed);
                ball.SetVelocity(velocity);
            }
        }
    }
    previousBallVelocity_ = velocity;
    previousBallTouchTime_ = touchTime;
    previousBallWorldHitTime_ = worldHitTime;
    hasBallPhysicsHistory_ = true;
}

bool TASPlugin::IsSafeFileName(const std::string& name) const {
    if (name == "=") return true;
    if (name.empty() || name.size() > 100 || name.find("..") != std::string::npos) return false;
    return std::all_of(name.begin(), name.end(), [](unsigned char c) {
        return std::isalnum(c) || c == '_' || c == '-' || c == '.';
    });
}

std::filesystem::path TASPlugin::DataPath(const std::string& name) const {
    std::string file = name;
    if (std::filesystem::path(file).extension().empty()) file += ".tas";
    return gameWrapper->GetDataFolder() / "TASPlugin" / file;
}

void TASPlugin::RefreshFileList() {
    std::vector<std::string> files;
    std::error_code ec;
    const auto folder = gameWrapper->GetDataFolder() / "TASPlugin";
    if (std::filesystem::exists(folder, ec)) {
        for (std::filesystem::directory_iterator it(folder, ec), end; !ec && it != end; it.increment(ec)) {
            if (!it->is_regular_file(ec) || it->path().extension() != ".tas") continue;
            files.push_back(it->path().stem().string());
        }
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!selectedFile_.empty() && std::find(files.begin(), files.end(), selectedFile_) == files.end())
            files.push_back(selectedFile_);
        std::sort(files.begin(), files.end());
        tasFiles_ = std::move(files);
    }
}

bool TASPlugin::SaveFile(const std::string& name) {
    if (!IsSafeFileName(name)) { SetStatus("Use a simple file name with letters, numbers, _ or -."); return false; }
    ActorState car, ball;
    std::vector<Frame> frames;
    size_t startFrameIndex = 0;
    bool hasState = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        hasState = hasStart_ || hasPendingStart_;
        if (hasState) {
            car = hasPendingStart_ ? pendingCar_ : startCar_;
            ball = hasPendingStart_ ? pendingBall_ : startBall_;
            frames = frames_;
            startFrameIndex = std::min(startFrameIndex_, frames.size());
        }
    }
    if (!hasState) { SetStatus("Create a TAS and set its start point before saving."); return false; }
    const auto path = DataPath(name);
    std::error_code ec;
    std::filesystem::create_directories(path.parent_path(), ec);
    std::ofstream out(path, std::ios::trunc);
    if (!out) { SetStatus("Could not create the TAS file."); return false; }
    out << "TASPLUGIN,4\n" << std::setprecision(9);
    WriteActor(out, car); out << '\n';
    WriteActor(out, ball); out << '\n';
    out << frames.size() << ',' << startFrameIndex << '\n';
    for (const auto& frame : frames) {
        const auto& i = frame.input;
        out << i.Throttle << ',' << i.Steer << ',' << i.Pitch << ',' << i.Yaw << ',' << i.Roll << ','
            << i.DodgeForward << ',' << i.DodgeStrafe << ',' << i.Handbrake << ',' << i.Jump << ','
            << i.ActivateBoost << ',' << i.HoldingBoost << ',' << i.Jumped << ',' << frame.hasBallState;
        if (frame.hasBallState) { out << ','; WriteActor(out, frame.ball); }
        out << '\n';
    }
    if (!out) { SetStatus("TAS file write failed."); return false; }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        selectedFile_ = name;
        startCar_ = car; startBall_ = ball; hasStart_ = true; hasPendingStart_ = false;
        startFrameIndex_ = startFrameIndex;
    }
    RefreshFileList();
    SetStatus("Saved " + path.filename().string() + " (" + std::to_string(frames.size()) + " frames).");
    return true;
}

void TASPlugin::LoadFile(const std::string& name) {
    if (!IsSafeFileName(name)) { SetStatus("Use a simple TAS file name."); return; }
    std::ifstream in(DataPath(name));
    std::string header;
    ActorState car, ball;
    size_t count = 0, startFrameIndex = 0;
    if (!std::getline(in, header) || (header != "TASPLUGIN,2" && header != "TASPLUGIN,3" && header != "TASPLUGIN,4") || !ReadActor(in, car) || !ReadActor(in, ball) || !(in >> count) || count > 2000000) {
        SetStatus("Could not load this TAS file or its format is invalid."); return;
    }
    if (header == "TASPLUGIN,4") {
        char comma = 0;
        if (!(in >> comma >> startFrameIndex) || comma != ',' || startFrameIndex > count) {
            SetStatus("TAS file has an invalid saved start point."); return;
        }
    }
    const bool hasFrameBallStates = header == "TASPLUGIN,3" || header == "TASPLUGIN,4";
    in.ignore(std::numeric_limits<std::streamsize>::max(), '\n');
    std::vector<Frame> loaded;
    loaded.reserve(count);
    for (size_t n = 0; n < count; ++n) {
        Frame f; char comma = 0; unsigned long handbrake=0, jump=0, boost=0, holding=0, jumped=0, hasFrameBall=0;
        auto& i = f.input;
        if (!(in >> i.Throttle >> comma >> i.Steer >> comma >> i.Pitch >> comma >> i.Yaw >> comma >> i.Roll >> comma
            >> i.DodgeForward >> comma >> i.DodgeStrafe >> comma >> handbrake >> comma >> jump >> comma >> boost
            >> comma >> holding >> comma >> jumped)) {
            SetStatus("TAS file ended before all frames were read."); return;
        }
        i.Handbrake = handbrake != 0; i.Jump = jump != 0; i.ActivateBoost = boost != 0; i.HoldingBoost = holding != 0; i.Jumped = jumped != 0;
        if (hasFrameBallStates) {
            if (!(in >> comma >> hasFrameBall)) { SetStatus("TAS file ended before all ball states were read."); return; }
            f.hasBallState = hasFrameBall != 0;
            if (f.hasBallState) {
                if (!(in >> comma) || !ReadActor(in, f.ball)) { SetStatus("TAS file contains an invalid ball state."); return; }
            }
        }
        loaded.push_back(f);
        in.ignore(std::numeric_limits<std::streamsize>::max(), '\n');
    }
    // Earlier v3 files stored the ball transform on every frame. Keep only
    // discontinuities so old TAS files also avoid teleporting the ball every
    // playback tick.
    ActorState lastBallKeyframe = ball;
    for (auto& frame : loaded) {
        if (!frame.hasBallState) continue;
        frame.hasBallState = IsBallStateJump(lastBallKeyframe, frame.ball);
        lastBallKeyframe = frame.ball;
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        frames_ = std::move(loaded); startCar_ = car; startBall_ = ball; hasStart_ = true;
        startFrameIndex_ = startFrameIndex; completePlayback_ = false;
        hasPendingStart_ = false; playhead_ = 0; mode_ = Mode::Idle; selectedFile_ = name;
    }
    RefreshFileList();
    SetStatus("Loaded " + name + " (" + std::to_string(count) + " frames).");
}

void TASPlugin::SetBind(int index, const std::string& key) {
    if (index < 0 || index >= kBindCount || key.empty() || key.size() > 31 || !std::all_of(key.begin(), key.end(), [](unsigned char c) { return std::isalnum(c) || c == '='; })) { SetStatus("Could not read that key. Click Set bind, then press one key or mouse button."); return; }
    const std::string command = kBindCommands[index];
    const std::string cvar = "tasplugin_named_bind_" + std::to_string(kBindCvarIndices[index]);
    const std::string oldKey = cvarManager->getCvar(cvar).getStringValue();
    gameWrapper->Execute([this, cvar, key, oldKey, command, index](GameWrapper*) {
        if (!oldKey.empty() && oldKey != key) {
            bool oldKeyUsedElsewhere = false;
            for (int other = 0; other < kBindCount; ++other) {
                if (other == index) continue;
                const std::string otherCvar = "tasplugin_named_bind_" + std::to_string(kBindCvarIndices[other]);
                if (cvarManager->getCvar(otherCvar).getStringValue() == oldKey) {
                    oldKeyUsedElsewhere = true;
                    break;
                }
            }
            if (!oldKeyUsedElsewhere) cvarManager->executeCommand("unbind " + oldKey, false);
        }
        cvarManager->getCvar(cvar).setValue(key);
        cvarManager->executeCommand("bind " + key + " \"" + command + "\"", false);
        cvarManager->executeCommand("writeconfig", false);
    });
    std::strncpy(bindInput_[index], key.c_str(), sizeof(bindInput_[index]) - 1);
    bindInput_[index][sizeof(bindInput_[index]) - 1] = '\0';
    SetStatus("Saved bind " + key + ".");
}

std::string TASPlugin::KeyNameFromVirtualKey(int key) {
    if (key >= 'A' && key <= 'Z') return std::string(1, static_cast<char>(key));
    if (key >= '0' && key <= '9') return std::string(1, static_cast<char>(key));
    if (key >= VK_F1 && key <= VK_F24) return "F" + std::to_string(key - VK_F1 + 1);
    switch (key) {
        case VK_OEM_PLUS: return "=";
        case VK_SPACE: return "Space";
        case VK_RETURN: return "Enter";
        case VK_TAB: return "Tab";
        case VK_ESCAPE: return "Escape";
        case VK_UP: return "Up";
        case VK_DOWN: return "Down";
        case VK_LEFT: return "Left";
        case VK_RIGHT: return "Right";
        case VK_OEM_MINUS: return "-";
        default: return {};
    }
}

void TASPlugin::CaptureBindInput() {
    if (bindCaptureIndex_ < 0) return;
    for (int key = 1; key < 256; ++key) {
        const bool down = (GetAsyncKeyState(key) & 0x8000) != 0;
        const bool pressed = down && !keyWasDown_[key];
        keyWasDown_[key] = down;
        if (!pressed || key == VK_SHIFT || key == VK_CONTROL || key == VK_MENU || key == VK_LWIN || key == VK_RWIN) continue;
        std::string name;
        if (key == VK_XBUTTON1) name = "Mouse4";
        else if (key == VK_XBUTTON2) name = "Mouse5";
        else if (key == VK_LBUTTON) name = "Mouse1";
        else if (key == VK_RBUTTON) name = "Mouse2";
        else if (key == VK_MBUTTON) name = "Mouse3";
        else name = KeyNameFromVirtualKey(key);
        if (!name.empty()) {
            const int index = bindCaptureIndex_;
            bindCaptureIndex_ = -1;
            SetBind(index, name);
            return;
        }
    }
}

void TASPlugin::SetStatus(const std::string& message) {
    std::lock_guard<std::mutex> lock(mutex_);
    status_ = message;
}

void TASPlugin::RenderSettings() {
    const bool captureWasActiveAtFrameStart = bindCaptureIndex_ >= 0;
    ImGui::TextUnformatted("TAS PLUGIN");
    ImGui::TextDisabled("Create named shots, replay them, then branch or continue from any point.");
    ImGui::Separator();
    if (ImGui::BeginTabBar("tasplugin_tabs")) {
        if (ImGui::BeginTabItem("Shot controls")) {
            if (newTasDialogRequested_.exchange(false)) ImGui::OpenPopup("Create New TAS");
            if (ImGui::Button("New TAS")) ImGui::OpenPopup("Create New TAS");
            ImGui::SameLine();
            if (ImGui::Button("Play The Shot")) gameWrapper->Execute([this](GameWrapper*) { StartOrReplay(); });
            ImGui::SameLine();
            if (ImGui::Button("Record from start")) gameWrapper->Execute([this](GameWrapper*) { RecordFromStart(); });
            ImGui::SameLine();
            if (ImGui::Button("Save")) gameWrapper->Execute([this](GameWrapper*) { SaveCurrentTas(); });
            if (ImGui::Button("Play Complete Shot")) gameWrapper->Execute([this](GameWrapper*) { PlayCompleteShot(); });
            ImGui::SameLine();
            if (ImGui::Button("Update Start Point")) gameWrapper->Execute([this](GameWrapper*) { UpdateStartPoint(); });
            ImGui::SameLine();
            if (ImGui::Button("Delete Current TAS")) ImGui::OpenPopup("Confirm Delete TAS");
            if (ImGui::BeginPopupModal("Confirm Delete TAS", nullptr, ImGuiWindowFlags_AlwaysAutoResize)) {
                ImGui::TextUnformatted("Delete the selected TAS file permanently?");
                if (ImGui::Button("Delete TAS")) {
                    gameWrapper->Execute([this](GameWrapper*) { DeleteCurrentTas(); });
                    ImGui::CloseCurrentPopup();
                }
                ImGui::SameLine();
                if (ImGui::Button("Keep TAS")) ImGui::CloseCurrentPopup();
                ImGui::EndPopup();
            }

            if (ImGui::BeginPopupModal("Create New TAS", nullptr, ImGuiWindowFlags_AlwaysAutoResize)) {
                ImGui::TextUnformatted("Give this shot a name. It will be created and selected without starting playback or recording.");
                ImGui::SetNextItemWidth(280.0f);
                ImGui::InputText("TAS name", newTasNameInput_, sizeof(newTasNameInput_));
                if (ImGui::Button("Create TAS")) {
                    const std::string name = newTasNameInput_;
                    gameWrapper->Execute([this, name](GameWrapper*) { NewShot(name); });
                    ImGui::CloseCurrentPopup();
                }
                ImGui::SameLine();
                if (ImGui::Button("Cancel")) ImGui::CloseCurrentPopup();
                ImGui::EndPopup();
            }

            std::vector<std::string> files;
            std::string currentFile;
            std::string modeLabel;
            size_t frameCount = 0;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                files = tasFiles_;
                currentFile = selectedFile_;
                frameCount = frames_.size();
                modeLabel = mode_ == Mode::Recording ? "Recording" :
                    (mode_ == Mode::Playing ? "Playing" : (mode_ == Mode::AtEndpoint ? "At endpoint" : "Stopped"));
            }
            const char* currentPreview = currentFile.empty() ? "Choose a TAS..." : currentFile.c_str();
            if (ImGui::BeginCombo("Select Current TAS", currentPreview)) {
                for (const auto& name : files) {
                    const bool selected = name == currentFile;
                    if (ImGui::Selectable(name.c_str(), selected) && !selected) {
                        gameWrapper->Execute([this, name](GameWrapper*) {
                            LoadFile(name);
                        });
                    }
                    if (selected) ImGui::SetItemDefaultFocus();
                }
                ImGui::EndCombo();
            }
            ImGui::Text("Current TAS: %s  |  %s  |  %zu recorded frames",
                currentFile.empty() ? "None" : currentFile.c_str(), modeLabel.c_str(), frameCount);
            ImGui::TextWrapped("New TAS resets Free Play to Rocket League's normal car and ball spawn, then creates a named shot. Record from start records your driving. Save keeps the take; Play The Shot replays the saved take and lets you take control to branch it.");
            ImGui::TextWrapped("Update Start Point moves the saved beginning to the current playback moment and keeps the earlier recorded frames. Play Complete Shot runs the saved take start-to-finish without branching, for capturing the whole shot.");
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Game settings")) {
            ImGui::TextUnformatted("Playback and game settings");
            ImGui::TextDisabled("Replay speed changes how quickly the recorded inputs advance. Adjust it while a shot is playing.");
            float replaySpeed, ballMultiplier;
            float topBinThresholdKph;
            PinchKind lastPinch;
            float lastPinchSpeedKph;
            { std::lock_guard<std::mutex> lock(mutex_); replaySpeed = playbackSpeed_; ballMultiplier = ballSpeedMultiplier_; topBinThresholdKph = topBinThresholdKph_; lastPinch = lastPinchKind_; lastPinchSpeedKph = lastPinchSpeedKph_; }
            ImGui::SetNextItemWidth(360.0f);
            if (ImGui::SliderFloat("Replay input speed", &replaySpeed, 0.0f, 2.0f, "%.2fx")) {
                replaySpeed = std::round(replaySpeed * 100.0f) / 100.0f;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    playbackSpeed_ = replaySpeed;
                    if (replaySpeed > 0.0f) gameSpeed_ = replaySpeed;
                }
                if (replaySpeed > 0.0f) {
                    gameWrapper->Execute([this, replaySpeed](GameWrapper*) {
                        cvarManager->getCvar("sv_soccar_gamespeed").setValue(replaySpeed);
                        cvarManager->getCvar("tasplugin_gamespeed").setValue(replaySpeed);
                        cvarManager->executeCommand("writeconfig", false);
                    });
                }
            }
            ImGui::TextDisabled("Above 0x, playback and game physics stay in sync to prevent the car and ball drifting off the recorded path. 0x holds the input while physics continue.");
            if (ImGui::Button(ballMultiplier > 1.5f ? "Ball speed: 2x" : "Ball speed: 1x")) cvarManager->executeCommand("tasplugin_toggle_ball_speed", false);
            ImGui::TextDisabled("Top-bin aim is automatic when the ball is heading into the goal and reaches the speed below.");
            ImGui::SetNextItemWidth(360.0f);
            if (ImGui::SliderFloat("Top-bin trigger speed", &topBinThresholdKph, 0.0f, 300.0f, "%.0f km/h")) {
                topBinThresholdKph = std::round(topBinThresholdKph);
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    topBinThresholdKph_ = topBinThresholdKph;
                }
                gameWrapper->Execute([this, topBinThresholdKph](GameWrapper*) {
                    cvarManager->getCvar("tasplugin_topbin_min_kph").setValue(topBinThresholdKph);
                    cvarManager->executeCommand("writeconfig", false);
                });
            }
            const char* pinchLabel = lastPinch == PinchKind::Wall ? "Wall pinch (Kuxir-style)" :
                (lastPinch == PinchKind::Ground ? "Ground pinch" :
                (lastPinch == PinchKind::Ceiling ? "Ceiling pinch" :
                (lastPinch == PinchKind::Unknown ? "Pinch (surface unclear)" : "None yet")));
            if (lastPinch == PinchKind::None)
                ImGui::Text("Last pinch detected: %s", pinchLabel);
            else
                ImGui::Text("Last pinch detected: %s | %.0f km/h", pinchLabel, lastPinchSpeedKph);
            ImGui::TextDisabled("Detection uses a close car touch, a world hit, and a sharp velocity change; unusual collisions may be unclassified.");
            ImGui::TextDisabled("Drag to set Rocket League game speed (0.50x to 2.00x).");
            ImGui::SetNextItemWidth(360.0f);
            if (ImGui::SliderFloat("Game speed", &gameSpeed_, 0.5f, 2.0f, "%.2fx")) {
                gameSpeed_ = std::round(gameSpeed_ * 100.0f) / 100.0f;
                const float speed = gameSpeed_;
                {
                    std::lock_guard<std::mutex> lock(mutex_);
                    playbackSpeed_ = speed;
                }
                gameWrapper->Execute([this, speed](GameWrapper*) {
                    cvarManager->getCvar("sv_soccar_gamespeed").setValue(speed);
                    cvarManager->getCvar("tasplugin_gamespeed").setValue(speed);
                    cvarManager->executeCommand("writeconfig", false);
                });
            }
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Binds")) {
            ImGui::TextDisabled("Click Set bind, then press a key or mouse button. Mouse4 and Mouse5 are side buttons. The Delete bind deletes the selected file immediately.");
            for (int i = 0; i < kBindCount; ++i) {
                ImGui::PushID(i);
                ImGui::TextUnformatted(kBindLabels[i]); ImGui::SameLine(270.0f);
                ImGui::Text("%s", bindInput_[i]); ImGui::SameLine(390.0f);
                const bool capturing = bindCaptureIndex_ == i;
                if (ImGui::Button(capturing ? "Press a key..." : "Set bind")) {
                    bindCaptureIndex_ = i;
                    std::fill(std::begin(keyWasDown_), std::end(keyWasDown_), false);
                }
                ImGui::PopID();
            }
            if (captureWasActiveAtFrameStart) CaptureBindInput();
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("About")) {
            ImGui::TextWrapped("Each named TAS stores its start state, saved start point, and controller inputs. Playback is restricted to offline Free Play and Custom Training.");
            ImGui::TextWrapped("Use Save to keep a take. Unsaved changes are discarded when you replay or switch TAS files. Original TAS Plugin files are not compatible.");
            ImGui::EndTabItem();
        }
        ImGui::EndTabBar();
    }
    ImGui::Separator();
    std::string status;
    { std::lock_guard<std::mutex> lock(mutex_); status = status_; }
    ImGui::TextWrapped("Status: %s", status.c_str());
}
