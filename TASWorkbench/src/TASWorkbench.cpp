#include "TASWorkbench.h"
#include "bakkesmod/wrappers/includes.h"
#include "imgui/imgui.h"
#include <algorithm>
#include <cctype>
#include <cstring>
#include <sstream>

BAKKESMOD_PLUGIN(TASWorkbench, "TAS Workbench", "1.0.0", PLUGINTYPE_FREEPLAY)

namespace {
constexpr int kMutatorCount = 16;
struct MutatorChoice {
    const char* label;
    const char* const* options;
    const char* const* tags;
    int count;
};

constexpr const char* kMatchLength[] = {"5 minutes (default)", "10 minutes", "20 minutes", "Unlimited"};
constexpr const char* kMatchLengthTag[] = {"", "10Minutes", "20Minutes", "UnlimitedTime"};
constexpr const char* kMaxScore[] = {"Default", "1 goal", "3 goals", "5 goals", "7 goals", "Unlimited"};
constexpr const char* kMaxScoreTag[] = {"", "Max1", "Max3", "Max5", "Max7", "UnlimitedScore"};
constexpr const char* kGameSpeed[] = {"Default", "Slow motion"};
constexpr const char* kGameSpeedTag[] = {"", "SloMoGameSpeed"};
constexpr const char* kBoostAmount[] = {"Default", "Unlimited"};
constexpr const char* kBoostAmountTag[] = {"", "UnlimitedBooster"};
constexpr const char* kBoostRecharge[] = {"Default", "Fast", "Slow"};
constexpr const char* kBoostRechargeTag[] = {"", "RapidRecharge", "SlowRecharge"};
constexpr const char* kBoostStrength[] = {"Default", "1.5x", "2x", "10x"};
constexpr const char* kBoostStrengthTag[] = {"", "BoostMultiplier1_5x", "BoostMultiplier2x", "BoostMultiplier10x"};
constexpr const char* kGravity[] = {"Default", "Low", "High", "Super high", "Almost zero", "Inverse"};
constexpr const char* kGravityTag[] = {"", "LowGravity", "HighGravity", "SuperGravity", "AlmostZeroGravity", "InverseGravity"};
constexpr const char* kDemolish[] = {"Default", "Disabled", "Always", "Always on opponents", "Everyone"};
constexpr const char* kDemolishTag[] = {"", "NoDemolish", "AlwaysDemolish", "AlwaysDemolishOpposing", "DemolishAll"};
constexpr const char* kRespawn[] = {"Default", "1 second", "2 seconds"};
constexpr const char* kRespawnTag[] = {"", "OneSecondsRespawn", "TwoSecondsRespawn"};
constexpr const char* kJumps[] = {"Default", "Double jump"};
constexpr const char* kJumpsTag[] = {"", "DoubleJump2x"};
constexpr const char* kBallSpeed[] = {"Default", "Slow", "Fast"};
constexpr const char* kBallSpeedTag[] = {"", "SlowBall", "FastBall"};
constexpr const char* kBallType[] = {"Default", "Cube", "Puck", "Rubber"};
constexpr const char* kBallTypeTag[] = {"", "Ball_CubeBall", "Ball_Puck", "Ball_RubberBouncyBall"};
constexpr const char* kBallSize[] = {"Default", "Small", "Big", "Giant"};
constexpr const char* kBallSizeTag[] = {"", "SmallBall", "BigBall", "GiantBall"};
constexpr const char* kBounce[] = {"Default", "Low", "High", "Super high"};
constexpr const char* kBounceTag[] = {"", "LowBounciness", "HighBounciness", "SuperBounciness"};
constexpr const char* kBallGravity[] = {"Default", "Low", "High"};
constexpr const char* kBallGravityTag[] = {"", "LowGravityBall", "HighGravityBall"};
constexpr const char* kBallCount[] = {"1 ball", "2 balls", "4 balls", "6 balls"};
constexpr const char* kBallCountTag[] = {"", "TwoBalls", "FourBalls", "SixBalls"};

const MutatorChoice kMutators[kMutatorCount] = {
    {"Match length", kMatchLength, kMatchLengthTag, 4},
    {"Max score", kMaxScore, kMaxScoreTag, 6},
    {"Game speed", kGameSpeed, kGameSpeedTag, 2},
    {"Boost amount", kBoostAmount, kBoostAmountTag, 2},
    {"Boost pad recharge", kBoostRecharge, kBoostRechargeTag, 3},
    {"Boost strength", kBoostStrength, kBoostStrengthTag, 4},
    {"Gravity", kGravity, kGravityTag, 6},
    {"Demolish", kDemolish, kDemolishTag, 5},
    {"Respawn time", kRespawn, kRespawnTag, 3},
    {"Jumps", kJumps, kJumpsTag, 2},
    {"Ball max speed", kBallSpeed, kBallSpeedTag, 3},
    {"Ball type", kBallType, kBallTypeTag, 4},
    {"Ball size", kBallSize, kBallSizeTag, 4},
    {"Ball bounciness", kBounce, kBounceTag, 4},
    {"Ball gravity", kBallGravity, kBallGravityTag, 3},
    {"Number of balls", kBallCount, kBallCountTag, 4}
};

bool SafeToken(const std::string& s) {
    if (s == "=") return true;
    return !s.empty() && std::all_of(s.begin(), s.end(), [](unsigned char c) {
        return std::isalnum(c) || c == '_' || c == '-';
    });
}

bool ComboRow(const MutatorChoice& choice, int& selected, const std::string& id) {
    selected = std::clamp(selected, 0, choice.count - 1);
    ImGui::SetNextItemWidth(190.0f);
    return ImGui::Combo(id.c_str(), &selected, choice.options, choice.count);
}
}

void TASWorkbench::onLoad() {
    const char* initialBinds[] = {"F7", "F8", "Mouse4", "="};
    for (int i = 0; i < 4; ++i) {
        const auto cvarName = "taswb_bind_" + std::to_string(i);
        cvarManager->registerCvar(cvarName, initialBinds[i], "TAS Workbench key binding");
        const auto value = cvarManager->getCvar(cvarName).getStringValue();
        std::strncpy(binds_[i].data(), value.c_str(), binds_[i].size() - 1);
        binds_[i].back() = '\0';
    }
    for (int i = 0; i < MutatorCount; ++i) {
        const auto name = "taswb_mutator_" + std::to_string(i);
        cvarManager->registerCvar(name, "0", "TAS Workbench mutator choice", true, true, 0, true,
                                  kMutators[i].count - 1);
        choices_[i] = std::clamp(cvarManager->getCvar(name).getIntValue(), 0, kMutators[i].count - 1);
    }
    RegisterCommands();
}

void TASWorkbench::onUnload() {}

void TASWorkbench::RegisterCommands() {
    cvarManager->registerNotifier("taswb_start", [this](std::vector<std::string>) {
        cvarManager->executeCommand("tas_start", false);
    }, "Replay the current TAS and begin a recording attempt", PERMISSION_OFFLINE);
    cvarManager->registerNotifier("taswb_stop", [this](std::vector<std::string>) {
        cvarManager->executeCommand("tas_stop", false);
    }, "Stop the current TAS playback or recording", PERMISSION_OFFLINE);
    cvarManager->registerNotifier("taswb_stop_update", [this](std::vector<std::string>) {
        cvarManager->executeCommand("tas_stopandupdate", false);
    }, "Stop and update through the TAS plugin", PERMISSION_OFFLINE);
    cvarManager->registerNotifier("taswb_speed", [this](std::vector<std::string>) {
        if (!gameWrapper->IsInFreeplay() && !gameWrapper->IsInCustomTraining()) return;
        const auto current = cvarManager->getCvar("sv_soccar_gamespeed").getFloatValue();
        const float next = current < 0.75f ? 1.0f : (current < 1.5f ? 2.0f : 0.5f);
        cvarManager->getCvar("sv_soccar_gamespeed").setValue(next);
        gameWrapper->Toast("TAS Workbench", "Game speed: " + std::to_string(next) + "x");
    }, "Cycle offline game speed: 0.5x, 1x, 2x", PERMISSION_FREEPLAY | PERMISSION_CUSTOM_TRAINING | PERMISSION_OFFLINE);
}

std::string TASWorkbench::BuildGameTags() const {
    std::vector<std::string> tags;
    for (int i = 0; i < MutatorCount; ++i) {
        const auto choice = std::clamp(choices_[i], 0, kMutators[i].count - 1);
        const char* tag = kMutators[i].tags[choice];
        if (tag && *tag) tags.emplace_back(tag);
    }
    std::ostringstream out;
    for (size_t i = 0; i < tags.size(); ++i) {
        if (i) out << ',';
        out << tags[i];
    }
    return out.str();
}

void TASWorkbench::CreateMutatorMatch() {
    const std::string map = mapName_;
    const std::string matchName = matchName_;
    if (!SafeToken(map) || matchName.empty()) {
        SetStatus("Map name must use letters, numbers, _ or -; match name cannot be blank.");
        return;
    }
    CustomMatchSettings settings{};
    settings.GameMode = 0;
    settings.MaxPlayerCount = 8;
    settings.bPartyMembersOnly = true;
    settings.MapName = map;
    settings.ServerName = matchName;
    settings.GameTags = BuildGameTags();
    settings.BlueTeamSettings.Name = "Blue";
    settings.OrangeTeamSettings.Name = "Orange";
    const Region region = regionIndex_ == 0 ? Region::EU : (regionIndex_ == 1 ? Region::SAF : Region::SAM);
    gameWrapper->Execute([this, region, settings](GameWrapper* gw) {
        if (!gw || !gw->GetMatchmakingWrapper()) {
            SetStatus("Matchmaking interface is unavailable.");
            return;
        }
        gw->GetMatchmakingWrapper().CreatePrivateMatch(region, static_cast<int>(PlaylistIds::PrivateMatch), settings);
        SetStatus("Requested a Soccar private match with these mutators.");
    });
}

void TASWorkbench::SetBind(int slot, const std::string& key) {
    static const char* commands[] = {"taswb_start", "taswb_stop", "taswb_stop_update", "taswb_speed"};
    if (slot < 0 || slot >= 4) return;
    if (!SafeToken(key)) {
        SetStatus("Use a key name such as F7, Mouse4, Mouse5, or =.");
        return;
    }
    std::strncpy(binds_[slot].data(), key.c_str(), binds_[slot].size() - 1);
    binds_[slot].back() = '\0';
    const std::string cvarName = "taswb_bind_" + std::to_string(slot);
    gameWrapper->Execute([this, key, cvarName, command = std::string(commands[slot])](GameWrapper*) {
        cvarManager->getCvar(cvarName).setValue(key);
        cvarManager->executeCommand("bind " + key + " \"" + command + "\"", false);
        cvarManager->executeCommand("writeconfig", false);
    });
    SetStatus("Saved bind: " + key);
}

void TASWorkbench::SetStatus(const std::string& text) {
    std::lock_guard<std::mutex> lock(statusMutex_);
    status_ = text;
}

std::string TASWorkbench::GetPluginName() { return "TASWorkbench"; }

void TASWorkbench::SetImGuiContext(uintptr_t ctx) {
    ImGui::SetCurrentContext(reinterpret_cast<ImGuiContext*>(ctx));
}

void TASWorkbench::RenderSettings() {
    ImGui::TextUnformatted("TAS WORKBENCH");
    ImGui::TextDisabled("Shot controls, mutators, and binds in one place.");
    ImGui::Separator();
    if (ImGui::BeginTabBar("taswb_tabs")) {
        if (ImGui::BeginTabItem("Shot")) {
            ImGui::TextUnformatted("TAS engine commands");
            ImGui::TextDisabled("These actions call the installed TAS Plugin commands.");
            if (ImGui::Button("Replay shot / start attempt")) gameWrapper->Execute([this](GameWrapper*) { cvarManager->executeCommand("taswb_start", false); });
            ImGui::SameLine();
            if (ImGui::Button("Stop")) gameWrapper->Execute([this](GameWrapper*) { cvarManager->executeCommand("taswb_stop", false); });
            if (ImGui::Button("Stop + update")) gameWrapper->Execute([this](GameWrapper*) { cvarManager->executeCommand("taswb_stop_update", false); });
            ImGui::SameLine();
            if (ImGui::Button("Cycle game speed")) gameWrapper->Execute([this](GameWrapper*) { cvarManager->executeCommand("taswb_speed", false); });
            ImGui::TextDisabled("Game speed cycles 0.5x → 1x → 2x in offline play.");
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Mutators")) {
            ImGui::TextUnformatted("Soccar · Custom Match");
            ImGui::TextDisabled("Uses Rocket League's custom-match mutator tags.");
            for (int i = 0; i < MutatorCount; ++i) {
                ImGui::PushID(i);
                ImGui::AlignTextToFramePadding();
                ImGui::TextUnformatted(kMutators[i].label);
                ImGui::SameLine(205.0f);
                if (ComboRow(kMutators[i], choices_[i], "##choice")) {
                    const auto cvarName = "taswb_mutator_" + std::to_string(i);
                    const int value = choices_[i];
                    gameWrapper->Execute([this, cvarName, value](GameWrapper*) { cvarManager->getCvar(cvarName).setValue(value); });
                }
                ImGui::PopID();
            }
            ImGui::Text("Region"); ImGui::SameLine(205.0f);
            const char* regions[] = {"Europe", "South Africa", "South America"};
            ImGui::SetNextItemWidth(190.0f);
            ImGui::Combo("##region", &regionIndex_, regions, 3);
            ImGui::InputText("Match name", matchName_, sizeof(matchName_));
            ImGui::InputText("Map package", mapName_, sizeof(mapName_));
            ImGui::TextDisabled("Map examples: EuroStadium_P, Mannfield_P, DFHStadium_P.");
            if (ImGui::Button("Create private match")) CreateMutatorMatch();
            ImGui::SameLine();
            if (ImGui::Button("Save mutator choices")) {
                gameWrapper->Execute([this](GameWrapper*) { cvarManager->executeCommand("writeconfig", false); });
                SetStatus("Mutator choices saved.");
            }
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("Binds")) {
            ImGui::TextDisabled("Type a key name. Mouse4 and Mouse5 work for side buttons.");
            const char* labels[] = {"Replay / attempt", "Stop", "Stop + update", "Cycle speed"};
            const char* ids[] = {"##bind_start", "##bind_stop", "##bind_update", "##bind_speed"};
            for (int i = 0; i < 4; ++i) {
                ImGui::PushID(i);
                ImGui::TextUnformatted(labels[i]); ImGui::SameLine(180.0f);
                ImGui::SetNextItemWidth(120.0f);
                ImGui::InputText(ids[i], binds_[i].data(), binds_[i].size()); ImGui::SameLine();
                if (ImGui::Button("Apply")) SetBind(i, binds_[i].data());
                ImGui::PopID();
            }
            ImGui::EndTabItem();
        }
        if (ImGui::BeginTabItem("About")) {
            ImGui::TextWrapped("The TAS buttons call the original plugin's documented console commands. The mutator tab creates a Soccar private match using the SDK's CustomMatchSettings.");
            ImGui::TextWrapped("This source project does not claim to implement start-point editing, undo history, top-bin aim, or TAS file management. Those require the original TAS internals or a separately validated recorder. No inactive placeholder controls are shown.");
            ImGui::EndTabItem();
        }
        ImGui::EndTabBar();
    }
    ImGui::Separator();
    std::string status;
    {
        std::lock_guard<std::mutex> lock(statusMutex_);
        status = status_;
    }
    ImGui::TextWrapped("Status: %s", status.c_str());
}
