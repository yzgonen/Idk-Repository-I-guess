#pragma once

#include "bakkesmod/plugin/bakkesmodplugin.h"
#include "bakkesmod/plugin/PluginSettingsWindow.h"
#include <array>
#include <mutex>
#include <string>

class TASWorkbench final : public BakkesMod::Plugin::BakkesModPlugin,
                           public BakkesMod::Plugin::PluginSettingsWindow {
public:
    void onLoad() override;
    void onUnload() override;
    void RenderSettings() override;
    std::string GetPluginName() override;
    void SetImGuiContext(uintptr_t ctx) override;

private:
    static constexpr int MutatorCount = 16;
    std::array<int, MutatorCount> choices_{};
    std::array<std::array<char, 32>, 4> binds_{};
    char matchName_[64] = "TAS Workbench";
    char mapName_[64] = "EuroStadium_P";
    int regionIndex_ = 0;
    mutable std::mutex statusMutex_;

    void RegisterCommands();
    void SetBind(int slot, const std::string& key);
    void CreateMutatorMatch();
    std::string BuildGameTags() const;
    void SetStatus(const std::string& text);
    std::string status_ = "Ready";
};
