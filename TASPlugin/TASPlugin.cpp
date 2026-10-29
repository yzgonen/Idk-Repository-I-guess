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
#include <sstream>

BAKKESMOD_PLUGIN(TASPlugin, "TAS Plugin", "2.0.0", PLUGINTYPE_FREEPLAY)

namespace {
constexpr const char* kMoveEvent = "Function TAGame.PlayerController_TA.PlayerMove";
constexpr const char* kBindCommands[] = {
    "tasplugin_new", "tasplugin_play", "tasplugin_stop", "tasplugin_update_start"
};

float Length(const Vector& v) { return std::sqrt(v.X * v.X + v.Y * v.Y + v.Z * v.Z); }
Vector Scale(const Vector& v, float s) { return Vector{v.X * s, v.Y * s, v.Z * s}; }
Vector Subtract(const Vector& a, const Vector& b) { return Vector{a.X-b.X, a.Y-b.Y, a.Z-b.Z}; }

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
    cvarManager->registerCvar("tasplugin_topbin_aim", "0", "Offline top-bin aim assist", true, true, 0, true, 1);
    gameSpeed_ = cvarManager->getCvar("tasplugin_gamespeed").getFloatValue();
    ballSpeedMultiplier_ = cvarManager->getCvar("tasplugin_ball_speed").getFloatValue();
    topBinAim_ = cvarManager->getCvar("tasplugin_topbin_aim").getBoolValue();
    const char* defaultBinds[] = {"F7", "F8", "F9", "F10"};
    for (int i = 0; i < 4; ++i) {
        const std::string cvar = "tasplugin_bind_" + std::to_string(i);
        cvarManager->registerCvar(cvar, defaultBinds[i], "TAS Plugin bind");
        const std::string key = cvarManager->getCvar(cvar).getStringValue();
        std::strncpy(bindInput_[i], key.c_str(), sizeof(bindInput_[i]) - 1);
        bindInput_[i][sizeof(bindInput_[i]) - 1] = '\0';
    }
    RegisterCommands();
    gameWrapper->HookEvent(kMoveEvent, [this](std::string) { ApplyPlaybackInput(); });
    gameWrapper->HookEventPost(kMoveEvent, [this](std::string) { OnPlayerMove(); });
    SetStatus("Ready — load a TAS or create a new shot in offline Free Play.");
}

void TASPlugin::onUnload() {
    gameWrapper->UnhookEvent(kMoveEvent);
    gameWrapper->UnhookEventPost(kMoveEvent);
    std::lock_guard<std::mutex> lock(mutex_);
    mode_ = Mode::Idle;
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
    cvarManager->registerNotifier("tasplugin_new", [this](std::vector<std::string>) { NewShot(); }, "Start a new TAS recording from the current car and ball positions", PERMISSION_FREEPLAY | PERMISSION_CUSTOM_TRAINING);
    cvarManager->registerNotifier("tasplugin_play", [this](std::vector<std::string>) { StartOrReplay(); }, "Replay the loaded TAS or begin recording if it is empty", PERMISSION_FREEPLAY | PERMISSION_CUSTOM_TRAINING);
    cvarManager->registerNotifier("tasplugin_stop", [this](std::vector<std::string>) { Stop(); }, "Stop TAS recording or playback", PERMISSION_FREEPLAY | PERMISSION_CUSTOM_TRAINING);
    cvarManager->registerNotifier("tasplugin_update_start", [this](std::vector<std::string>) { UpdateStartPoint(); }, "Update the start position for the next replay without stopping recording", PERMISSION_FREEPLAY | PERMISSION_CUSTOM_TRAINING);
    cvarManager->registerNotifier("tasplugin_undo", [this](std::vector<std::string>) { Undo(); }, "Undo the last recorded input frame", PERMISSION_FREEPLAY | PERMISSION_CUSTOM_TRAINING);
    cvarManager->registerNotifier("tasplugin_save", [this](std::vector<std::string> args) { SaveFile(args.size() > 1 ? args[1] : selectedFile_); }, "Save the current TAS file", PERMISSION_FREEPLAY | PERMISSION_CUSTOM_TRAINING);
    cvarManager->registerNotifier("tasplugin_load", [this](std::vector<std::string> args) { if (args.size() > 1) LoadFile(args[1]); }, "Load a TAS file by name", PERMISSION_FREEPLAY | PERMISSION_CUSTOM_TRAINING);
}

void TASPlugin::NewShot() {
    if (!IsOffline()) { SetStatus("TAS recording works only in Free Play or Custom Training."); return; }
    ActorState car, ball;
    if (!CaptureState(car, ball)) { SetStatus("Could not read the current car and ball state."); return; }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        frames_.clear();
        startCar_ = car;
        startBall_ = ball;
        hasStart_ = true;
        hasPendingStart_ = false;
        playhead_ = 0;
        mode_ = Mode::Recording;
    }
    SetStatus("Recording from the current position. Use Stop, then Save TAS.");
}

void TASPlugin::StartOrReplay() {
    if (!IsOffline()) { SetStatus("TAS playback works only in Free Play or Custom Training."); return; }
    bool recording = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (hasPendingStart_) {
            startCar_ = pendingCar_;
            startBall_ = pendingBall_;
            hasStart_ = true;
            hasPendingStart_ = false;
        }
        if (frames_.empty()) {
            if (!hasStart_) {
                ActorState car, ball;
                if (!CaptureState(car, ball)) { status_ = "Could not read the current car and ball state."; return; }
                startCar_ = car; startBall_ = ball; hasStart_ = true;
            }
            mode_ = Mode::Recording;
            recording = true;
        } else {
            if (!hasStart_) { status_ = "This TAS has no start point. Create a new TAS first."; return; }
            mode_ = Mode::Playing;
            playhead_ = 0;
        }
    }
    if (recording) {
        SetStatus("Recording started.");
    } else {
        RestoreStart();
        SetStatus("Replaying TAS from frame 1.");
    }
}

void TASPlugin::Stop() {
    Mode oldMode;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        oldMode = mode_;
        mode_ = Mode::Idle;
    }
    if (oldMode == Mode::Recording) SetStatus("Recording stopped. Save TAS to keep the frames.");
    else if (oldMode == Mode::Playing) SetStatus("Playback stopped.");
    else SetStatus("TAS is already stopped.");
}

void TASPlugin::UpdateStartPoint() {
    ActorState car, ball;
    if (!CaptureState(car, ball)) { SetStatus("Start point can only be updated in offline Free Play or Custom Training."); return; }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        pendingCar_ = car;
        pendingBall_ = ball;
        hasPendingStart_ = true;
    }
    SetStatus("Start point updated. Recording continues; the next replay starts here.");
}

void TASPlugin::Undo() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (frames_.empty()) { status_ = "Nothing to undo."; return; }
    frames_.pop_back();
    if (playhead_ > frames_.size()) playhead_ = frames_.size();
    status_ = "Removed the last recorded input frame.";
}

void TASPlugin::OnPlayerMove() {
    if (!IsOffline()) return;
    PlayerControllerWrapper controller = gameWrapper->GetPlayerController();
    if (controller.IsNull()) return;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (mode_ == Mode::Recording) {
            frames_.push_back({controller.GetVehicleInput()});
        }
    }

    BallWrapper ball = gameWrapper->GetCurrentGameState().GetBall();
    if (ball.IsNull()) return;
    Vector velocity = ball.GetVelocity();
    const float oldSpeed = Length(previousBallVelocity_);
    float newSpeed = Length(velocity);
    if (ballSpeedMultiplier_ > 1.01f && newSpeed > 1.0f && oldSpeed > 1.0f && std::fabs(newSpeed - oldSpeed) > 1000.0f) {
        velocity = Scale(velocity, ballSpeedMultiplier_);
        ball.SetVelocity(velocity);
        newSpeed *= ballSpeedMultiplier_;
    }
    if (topBinAim_ && std::fabs(newSpeed - oldSpeed) > 1500.0f && newSpeed > 1000.0f) {
        const Vector loc = ball.GetLocation();
        const float goalY = velocity.Y >= 0.0f ? 5120.0f : -5120.0f;
        const float goalX = loc.X >= 0.0f ? 850.0f : -850.0f;
        Vector direction{goalX - loc.X, goalY - loc.Y, 620.0f - loc.Z};
        const float length = Length(direction);
        if (length > 1.0f) {
            velocity = Scale(direction, newSpeed / length);
            ball.SetVelocity(velocity);
        }
    }
    previousBallVelocity_ = velocity;
}

void TASPlugin::ApplyPlaybackInput() {
    if (!IsOffline()) return;
    PlayerControllerWrapper controller = gameWrapper->GetPlayerController();
    if (controller.IsNull()) return;
    ControllerInput input{};
    bool apply = false;
    bool finished = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (mode_ != Mode::Playing) return;
        if (playhead_ >= frames_.size()) {
            mode_ = Mode::Idle;
            status_ = "Playback finished.";
            finished = true;
        } else {
            input = frames_[playhead_++].input;
            apply = true;
        }
    }
    if (apply) controller.SetVehicleInput(input);
    if (finished) controller.SetVehicleInput(ControllerInput{});
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

void TASPlugin::SaveFile(const std::string& name) {
    if (!IsSafeFileName(name)) { SetStatus("Use a simple file name with letters, numbers, _ or -."); return; }
    ActorState car, ball;
    std::vector<Frame> frames;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!hasStart_ && !hasPendingStart_) { SetStatus("Create a TAS and set its start point before saving."); return; }
        car = hasPendingStart_ ? pendingCar_ : startCar_;
        ball = hasPendingStart_ ? pendingBall_ : startBall_;
        frames = frames_;
    }
    const auto path = DataPath(name);
    std::error_code ec;
    std::filesystem::create_directories(path.parent_path(), ec);
    std::ofstream out(path, std::ios::trunc);
    if (!out) { SetStatus("Could not create the TAS file."); return; }
    out << "TASPLUGIN,2\n" << std::setprecision(9);
    WriteActor(out, car); out << '\n';
    WriteActor(out, ball); out << '\n';
    out << frames.size() << '\n';
    for (const auto& frame : frames) {
        const auto& i = frame.input;
        out << i.Throttle << ',' << i.Steer << ',' << i.Pitch << ',' << i.Yaw << ',' << i.Roll << ','
            << i.DodgeForward << ',' << i.DodgeStrafe << ',' << i.Handbrake << ',' << i.Jump << ','
            << i.ActivateBoost << ',' << i.HoldingBoost << ',' << i.Jumped << '\n';
    }
    if (!out) { SetStatus("TAS file write failed."); return; }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        selectedFile_ = name;
        std::strncpy(fileInput_, name.c_str(), sizeof(fileInput_) - 1);
        fileInput_[sizeof(fileInput_) - 1] = '\0';
        startCar_ = car; startBall_ = ball; hasStart_ = true; hasPendingStart_ = false;
    }
    SetStatus("Saved " + path.filename().string() + " (" + std::to_string(frames.size()) + " frames).");
}

void TASPlugin::LoadFile(const std::string& name) {
    if (!IsSafeFileName(name)) { SetStatus("Use a simple TAS file name."); return; }
    std::ifstream in(DataPath(name));
    std::string header;
    ActorState car, ball;
    size_t count = 0;
    if (!std::getline(in, header) || header != "TASPLUGIN,2" || !ReadActor(in, car) || !ReadActor(in, ball) || !(in >> count) || count > 2000000) {
        SetStatus("Could not load this TAS file or its format is invalid."); return;
    }
    in.ignore(std::numeric_limits<std::streamsize>::max(), '\n');
    std::vector<Frame> loaded;
    loaded.reserve(count);
    for (size_t n = 0; n < count; ++n) {
        Frame f; char comma = 0; unsigned long handbrake=0, jump=0, boost=0, holding=0, jumped=0;
        auto& i = f.input;
        if (!(in >> i.Throttle >> comma >> i.Steer >> comma >> i.Pitch >> comma >> i.Yaw >> comma >> i.Roll >> comma
            >> i.DodgeForward >> comma >> i.DodgeStrafe >> comma >> handbrake >> comma >> jump >> comma >> boost
            >> comma >> holding >> comma >> jumped)) {
            SetStatus("TAS file ended before all frames were read."); return;
        }
        i.Handbrake = handbrake != 0; i.Jump = jump != 0; i.ActivateBoost = boost != 0; i.HoldingBoost = holding != 0; i.Jumped = jumped != 0;
        loaded.push_back(f);
        in.ignore(std::numeric_limits<std::streamsize>::max(), '\n');
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        frames_ = std::move(loaded); startCar_ = car; startBall_ = ball; hasStart_ = true;
        hasPendingStart_ = false; playhead_ = 0; mode_ = Mode::Idle; selectedFile_ = name;
        std::strncpy(fileInput_, name.c_str(), sizeof(fileInput_) - 1);
        fileInput_[sizeof(fileInput_) - 1] = '\0';
    }
    SetStatus("Loaded " + name + " (" + std::to_string(count) + " frames).");
}

void TASPlugin::SetBind(int index, const std::string& key) {
    if (index < 0 || index >= 4 || !IsSafeFileName(key)) { SetStatus("Enter a valid key name such as F7, Mouse4, or =."); return; }
    static const char* commands[] = {"tasplugin_new", "tasplugin_play", "tasplugin_stop", "tasplugin_update_start"};
    const std::string cvar = "tasplugin_bind_" + std::to_string(index);
    gameWrapper->Execute([this, cvar, key, command = std::string(commands[index])](GameWrapper*) {
        cvarManager->getCvar(cvar).setValue(key);
        cvarManager->executeCommand("bind " + key + " \"" + command + "\"", false);
        cvarManager->executeCommand("writeconfig", false);
    });
    std::strncpy(bindInput_[index], key.c_str(), sizeof(bindInput_[index]) - 1);
    bindInput_[index][sizeof(bindInput_[index]) - 1] = '\0';
    SetStatus("Saved bind " + key + ".");
}

void TASPlugin::SetStatus(const std::string& message) {
    std::lock_guard<std::mutex> lock(mutex_);
    status_ = message;
}

void TASPlugin::RenderSettings() {
    ImGui::TextUnformatted("TAS PLUGIN");
    ImGui::TextDisabled("Record, replay, update the start point, and manage TAS files.");
    ImGui::Separator();
    if (ImGui::BeginTabBar("tasplugin_tabs")) {
        if (ImGui::BeginTabItem("Shot controls")) {
            if (ImGui::Button("New TAS / start recording")) cvarManager->executeCommand("tasplugin_new", false);
            ImGui::SameLine();
            if (ImGui::Button("Play / record")) cvarManager->executeCommand("tasplugin_play", false);
            ImGui::SameLine();
            if (ImGui::Button("Stop")) cvarManager->executeCommand("tasplugin_stop", false);
            if (ImGui::Button("Update next start point")) cvarManager->executeCommand("tasplugin_update_start", false);
            ImGui::SameLine();
            if (ImGui::Button("Undo last frame")) cvarManager->executeCommand("tasplugin_undo", false);
            ImGui::TextDisabled("New TAS stores the current car and ball state. Stop, then save from Files.");
            std::lock_guard<std::mutex> lock(mutex_);
            ImGui::Text("Frames: %d", static_cast<int>(frames_.size()));
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Files")) {
            ImGui::TextUnformatted("TAS files");
            ImGui::TextDisabled("Files are saved in BakkesMod\\data\\TASPlugin.");
            ImGui::SetNextItemWidth(220.0f);
            ImGui::InputText("File name", fileInput_, sizeof(fileInput_));
            ImGui::SameLine();
            if (ImGui::Button("Save TAS")) SaveFile(fileInput_);
            ImGui::SameLine();
            if (ImGui::Button("Load TAS")) LoadFile(fileInput_);
            ImGui::TextDisabled("Use .tas files created by this plugin. Loading replaces the current in-memory shot.");
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Game settings")) {
            ImGui::TextUnformatted("Offline game settings");
            ImGui::TextDisabled("Uses Rocket League's game speed and the live ball velocity; no custom-match mutator screen.");
            const char* speeds[] = {"0.5x", "1x", "2x"};
            int speedChoice = gameSpeed_ < 0.75f ? 0 : (gameSpeed_ < 1.5f ? 1 : 2);
            if (ImGui::Combo("Game speed", &speedChoice, speeds, 3)) {
                const float values[] = {0.5f, 1.0f, 2.0f};
                gameSpeed_ = values[speedChoice];
                gameWrapper->Execute([this](GameWrapper*) { cvarManager->getCvar("sv_soccar_gamespeed").setValue(gameSpeed_); });
            }
            const char* ballSpeeds[] = {"1x", "2x"};
            int ballChoice = ballSpeedMultiplier_ > 1.5f ? 1 : 0;
            if (ImGui::Combo("Ball speed", &ballChoice, ballSpeeds, 2)) {
                ballSpeedMultiplier_ = ballChoice == 1 ? 2.0f : 1.0f;
                cvarManager->getCvar("tasplugin_ball_speed").setValue(ballSpeedMultiplier_);
            }
            if (ImGui::Checkbox("Top-bin aim assist (offline)", &topBinAim_))
                cvarManager->getCvar("tasplugin_topbin_aim").setValue(topBinAim_ ? 1 : 0);
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Binds")) {
            ImGui::TextDisabled("Four bind slots, matching the TAS controls. Mouse4 / Mouse5 are supported.");
            const char* labels[] = {"New TAS / record", "Play / record", "Stop", "Update next start"};
            for (int i = 0; i < 4; ++i) {
                ImGui::PushID(i);
                ImGui::TextUnformatted(labels[i]); ImGui::SameLine(190.0f);
                ImGui::SetNextItemWidth(100.0f);
                ImGui::InputText("##bind", bindInput_[i], sizeof(bindInput_[i])); ImGui::SameLine();
                if (ImGui::Button("Apply")) SetBind(i, bindInput_[i]);
                ImGui::PopID();
            }
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("About")) {
            ImGui::TextWrapped("TASPlugin records controller inputs on the PlayerMove event and replays them from a saved car and ball start state. It is restricted to offline Free Play and Custom Training.");
            ImGui::TextWrapped("This is an independent plugin implementation. Files made by the original TAS plugin are not compatible.");
            ImGui::EndTabItem();
        }
        ImGui::EndTabBar();
    }
    ImGui::Separator();
    std::string status;
    { std::lock_guard<std::mutex> lock(mutex_); status = status_; }
    ImGui::TextWrapped("Status: %s", status.c_str());
}
