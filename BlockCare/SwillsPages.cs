using System.Net;
using System.Net.NetworkInformation;
using System.Text.Json;
using Microsoft.Win32;

namespace BlockCare;

partial class Dashboard
{
    readonly Dictionary<string, CheckBox> stagedOptions = new();
    readonly string[] gameLibrary = { "Minecraft", "Fortnite", "Valorant", "Counter-Strike 2", "Apex Legends", "Overwatch 2", "GTA V", "League of Legends", "PUBG", "Rocket League" };
    bool latencyAdvanced, networkAdvanced, networkMore;
    string latencySubtab = "Scheduling";

    void ShowOptimizerPage(int module)
    {
        pageHost.Controls.Clear();
        var root = new Panel { Dock = DockStyle.Fill, BackColor = Background, Padding = new Padding(28, 16, 24, 20) };
        pageHost.Controls.Add(root);
        var title = module switch { 4 => "Latency", 5 => "Network", _ => "Games & Apps" };
        var heading = new Label { Text = title, Location = new Point(4, 0), AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 22, FontStyle.Bold) };
        root.Controls.Add(heading);
        var canvas = new Panel { Location = new Point(0, 52), Size = new Size(Math.Max(580, root.ClientSize.Width), Math.Max(450, root.ClientSize.Height - 52)), Anchor = AnchorStyles.Top | AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right, BackColor = Background, AutoScroll = true };
        root.Controls.Add(canvas);
        root.Resize += (_, _) => { if (!root.IsDisposed) canvas.Size = new Size(Math.Max(580, root.ClientSize.Width), Math.Max(450, root.ClientSize.Height - 52)); };
        if (module == 4) BuildLatencyPage(canvas);
        else if (module == 5) BuildNetworkPage(canvas);
        else BuildGameLibrary(canvas);
        status.Text = "ZENN  /  " + title.ToUpperInvariant();
    }

    Button SectionTab(string text, bool selected, int x, Action? click = null)
    {
        var b = new Button { Text = text, Location = new Point(x, 0), Size = new Size(Math.Max(112, text.Length * 8 + 30), 42), BackColor = selected ? Card : Background, ForeColor = selected ? Color.White : SecondaryText, FlatStyle = FlatStyle.Flat, Font = new Font("Segoe UI Semibold", 9), Cursor = Cursors.Hand };
        b.FlatAppearance.BorderColor = selected ? Color.FromArgb(55, 59, 57) : Background;
        b.FlatAppearance.BorderSize = 1;
        if (selected) { var underline = new Panel { Location = new Point(10, 41), Size = new Size(b.Width - 20, 2), BackColor = Green }; b.Controls.Add(underline); }
        if (click != null) b.Click += (_, _) => click();
        return b;
    }

    Label SectionHeading(string title, string desc, int x, int y)
    {
        var label = new Label { Text = title + "\n" + desc, Location = new Point(x, y), Size = new Size(850, 53), ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 13), AutoSize = false };
        label.Paint += (_, e) => { using var f = new Font("Segoe UI", 9); e.Graphics.DrawString(desc, f, new SolidBrush(SecondaryText), new PointF(0, 27)); };
        return label;
    }

    Panel SettingCard(string title, string description, bool checkedValue, int x, int y, int width, Action<bool>? changed = null, string? key = null)
    {
        var card = new Panel { Location = new Point(x, y), Size = new Size(width, 78), BackColor = Card, BorderStyle = BorderStyle.FixedSingle };
        var toggle = new CheckBox { Appearance = Appearance.Button, Text = checkedValue ? "●" : "○", TextAlign = ContentAlignment.MiddleCenter, Location = new Point(14, 19), Size = new Size(40, 38), Checked = checkedValue, BackColor = Color.FromArgb(12, 14, 13), ForeColor = checkedValue ? Green : SecondaryText, FlatStyle = FlatStyle.Flat, Cursor = Cursors.Hand };
        toggle.FlatAppearance.BorderColor = Color.FromArgb(48, 51, 50); toggle.FlatAppearance.BorderSize = 1;
        toggle.CheckedChanged += (_, _) => { toggle.Text = toggle.Checked ? "●" : "○"; toggle.ForeColor = toggle.Checked ? Green : SecondaryText; changed?.Invoke(toggle.Checked); };
        card.Controls.Add(toggle);
        card.Controls.Add(new Label { Text = title, Location = new Point(67, 14), AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 10) });
        card.Controls.Add(new Label { Text = description, Location = new Point(67, 39), Size = new Size(width - 78, 30), ForeColor = SecondaryText, Font = new Font("Segoe UI", 8.5f) });
        if (key != null) stagedOptions[key] = toggle;
        return card;
    }

    void BuildLatencyPage(Panel canvas)
    {
        stagedOptions.Clear();
        var tabs = new FlowLayoutPanel { Location = new Point(0, 0), Size = new Size(850, 46), BackColor = Background, WrapContents = false };
        foreach (var t in new[] { "Scheduling", "Keyboard & Mouse", "Power Plan", "··· And Many More" })
        {
            var tab = SectionTab(t, t == latencySubtab, 0, () => { if (t == "··· And Many More") { MessageBox.Show("More controls will appear here as Zenn gains verified, reversible options for your PC.", "Zenn"); return; } latencySubtab = t; ShowOptimizerPage(4); });
            tabs.Controls.Add(tab);
        }
        canvas.Controls.Add(tabs);
        var width = Math.Max(500, canvas.ClientSize.Width - 35);
        var content = new Panel { Location = new Point(0, 58), Size = new Size(width, 470), Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right, BackColor = Background };
        canvas.Controls.Add(content); canvas.Resize += (_, _) => { if (!content.IsDisposed) content.Width = Math.Max(500, canvas.ClientSize.Width - 35); };
        content.Controls.Add(new Label { Text = latencySubtab, Location = new Point(0, 0), AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 15) });
        content.Controls.Add(new Label { Text = latencySubtab == "Keyboard & Mouse" ? "Tune pointer behavior with a saved Windows restore point." : latencySubtab == "Power Plan" ? "Choose a reversible Windows power profile." : "Choose which reversible Windows gaming settings Zenn applies.", Location = new Point(0, 31), AutoSize = true, ForeColor = SecondaryText });
        var col = Math.Max(250, (width - 14) / 2);
        content.Controls.Add(SettingCard("Preset", "Apply the recommended configuration.", !latencyAdvanced, 0, 67, col, v => { latencyAdvanced = !v; ShowOptimizerPage(4); }, key: "preset"));
        content.Controls.Add(SettingCard("Advanced", "Configure each optimization individually.", latencyAdvanced, col + 12, 67, col, v => { latencyAdvanced = v; ShowOptimizerPage(4); }));
        if (latencyAdvanced && latencySubtab == "Scheduling")
        {
            content.Controls.Add(SettingCard("Windows Game Mode", "Tell Windows to prioritize the game while it is active.", true, 0, 157, col, key: "game-mode"));
        }
        if (latencyAdvanced && latencySubtab == "Keyboard & Mouse")
        {
            content.Controls.Add(SettingCard("Disable mouse acceleration", "Keep pointer movement closer to a 1:1 relationship with mouse input.", true, 0, 157, col, key: "mouse"));
        }
        if (latencyAdvanced && latencySubtab == "Power Plan")
        {
            content.Controls.Add(SettingCard("High Performance plan", "Uses more power and may increase heat. FPS gains are not guaranteed.", true, 0, 157, col, key: "power"));
        }
        var selectedKey = latencySubtab switch { "Keyboard & Mouse" => "mouse", "Power Plan" => "power", _ => "game-mode" };
        if (!latencyAdvanced) stagedOptions[selectedKey] = stagedOptions["preset"];
        content.Controls.Add(new Label { Text = "Windows changes are backed up before applying. Revert restores the original values.", Location = new Point(4, latencyAdvanced ? 247 : 157), AutoSize = true, ForeColor = SecondaryText, Font = new Font("Segoe UI", 8.5f) });
        var footer = Footer(canvas, () => ApplyLatency(), () => RevertLatency()); footer.Location = new Point(0, 444); footer.Anchor = AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right; canvas.Controls.Add(footer);
        canvas.Resize += (_, _) => footer.Location = new Point(0, Math.Max(444, canvas.ClientSize.Height - footer.Height - 4));
    }

    Panel Footer(Control parent, Func<Task> apply, Action revert)
    {
        var bar = new Panel { Size = new Size(Math.Max(550, parent.ClientSize.Width - 20), 64), BackColor = Card, Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Bottom, BorderStyle = BorderStyle.FixedSingle };
        var dashboard = SecondaryButton("←  Dashboard", 160); dashboard.Location = new Point(12, 12); dashboard.Click += (_, _) => { SelectNavigation(0); ShowDashboard(); }; bar.Controls.Add(dashboard);
        var revertButton = SecondaryButton("↶  Revert", 150); revertButton.Anchor = AnchorStyles.Right | AnchorStyles.Top; revertButton.Location = new Point(bar.Width - 328, 12); revertButton.Click += (_, _) => revert(); bar.Controls.Add(revertButton);
        var applyButton = MakeGreenButton("✓  Apply", 172); applyButton.Anchor = AnchorStyles.Right | AnchorStyles.Top; applyButton.Location = new Point(bar.Width - 166, 10); applyButton.Height = 42; applyButton.Click += async (_, _) => { applyButton.Enabled = false; try { await apply(); } catch (Exception ex) { MessageBox.Show(ex.Message, "Zenn"); } finally { if (!bar.IsDisposed) applyButton.Enabled = true; } }; bar.Controls.Add(applyButton);
        bar.Resize += (_, _) => { revertButton.Left = bar.Width - 328; applyButton.Left = bar.Width - 166; };
        return bar;
    }

    Button SecondaryButton(string text, int width) { var b = new Button { Text = text, Size = new Size(width, 40), BackColor = Color.FromArgb(31, 32, 33), ForeColor = Color.White, FlatStyle = FlatStyle.Flat, Font = new Font("Segoe UI Semibold", 9), Cursor = Cursors.Hand }; b.FlatAppearance.BorderColor = Color.FromArgb(55, 57, 58); return b; }

    sealed class OptimizerBackup { public string? Power { get; set; } public Dictionary<string, int?> Values { get; set; } = new(); }
    string OptimizerBackupPath => Path.Combine(data, "optimizer-settings-backup.json");
    static readonly string GameModeReg = @"Software\Microsoft\GameBar";
    static readonly string MouseReg = @"Control Panel\Mouse";
    Task ApplyLatency()
    {
        if (!stagedOptions.Values.Any(c => c.Checked)) { MessageBox.Show("Choose at least one setting to apply.", "Zenn"); return Task.CompletedTask; }
        if (File.Exists(OptimizerBackupPath)) { MessageBox.Show("A restore point already exists. Revert it before applying another set of Windows changes.", "Zenn"); return Task.CompletedTask; }
        var backup = new OptimizerBackup();
        void Save(string path, string name) => backup.Values[path + "|" + name] = ReadDword(path, name);
        var gameMode = stagedOptions.GetValueOrDefault("game-mode")?.Checked == true;
        var mouse = stagedOptions.GetValueOrDefault("mouse")?.Checked == true;
        var power = stagedOptions.GetValueOrDefault("power")?.Checked == true;
        if (gameMode) { Save(GameModeReg, "AutoGameModeEnabled"); Save(GameModeReg, "AllowAutoGameMode"); }
        if (mouse) { foreach (var n in new[] { "MouseSpeed", "MouseThreshold1", "MouseThreshold2" }) Save(MouseReg, n); }
        if (power)
        {
            var current = Process.Start(new ProcessStartInfo("powercfg.exe", "/getactivescheme") { UseShellExecute = false, RedirectStandardOutput = true, CreateNoWindow = true });
            backup.Power = current?.StandardOutput.ReadToEnd(); current?.WaitForExit();
            var match = System.Text.RegularExpressions.Regex.Match(backup.Power ?? "", @"[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
            if (!match.Success) throw new InvalidOperationException("Zenn couldn't read the current power plan. Nothing was changed.");
            backup.Power = match.Value;
        }
        Directory.CreateDirectory(data); File.WriteAllText(OptimizerBackupPath, JsonSerializer.Serialize(backup));
        try
        {
            if (gameMode) { WriteDword(GameModeReg, "AutoGameModeEnabled", 1); WriteDword(GameModeReg, "AllowAutoGameMode", 1); }
            if (mouse) { WriteDword(MouseReg, "MouseSpeed", 0); WriteDword(MouseReg, "MouseThreshold1", 0); WriteDword(MouseReg, "MouseThreshold2", 0); }
            if (power) { using var p = Process.Start(new ProcessStartInfo("powercfg.exe", "/setactive SCHEME_MIN") { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true }); p?.WaitForExit(); if (p?.ExitCode != 0) throw new InvalidOperationException(p?.StandardError.ReadToEnd()); }
            MessageBox.Show("Selected settings applied. Windows Game Mode and Mouse settings are stored for your user; the previous power plan and registry values are saved. Use Revert to restore them.", "Zenn");
        }
        catch { try { RestoreLatencyBackup(); } catch { } throw; }
        return Task.CompletedTask;
    }
    void RestoreLatencyBackup()
    {
        if (!File.Exists(OptimizerBackupPath)) { MessageBox.Show("There is no saved Zenn restore point.", "Zenn"); return; }
        var backup = JsonSerializer.Deserialize<OptimizerBackup>(File.ReadAllText(OptimizerBackupPath)) ?? throw new InvalidOperationException("Restore point is unreadable.");
        foreach (var pair in backup.Values) { var cut = pair.Key.LastIndexOf('|'); if (cut < 0) continue; WriteDword(pair.Key[..cut], pair.Key[(cut + 1)..], pair.Value); }
        if (Guid.TryParse(backup.Power, out _))
        {
            using var p = Process.Start(new ProcessStartInfo("powercfg.exe", "/setactive " + backup.Power) { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true }); p?.WaitForExit(); if (p?.ExitCode != 0) throw new InvalidOperationException("Windows could not restore the previous power plan.");
        }
        File.Delete(OptimizerBackupPath); MessageBox.Show("The saved Windows settings and power plan have been restored.", "Zenn");
    }
    void RevertLatency() { try { RestoreLatencyBackup(); } catch (Exception ex) { MessageBox.Show(ex.Message, "Zenn"); } }

    void BuildNetworkPage(Panel canvas)
    {
        stagedOptions.Clear();
        var tabs = new FlowLayoutPanel { Location = new Point(0, 0), Size = new Size(720, 46), BackColor = Background, WrapContents = false };
        tabs.Controls.Add(SectionTab("▥  Connection", !networkMore, 0, () => { networkMore = false; ShowOptimizerPage(5); }));
        tabs.Controls.Add(SectionTab("···  And Many More", networkMore, 0, () => { networkMore = true; ShowOptimizerPage(5); })); canvas.Controls.Add(tabs);
        var width = Math.Max(500, canvas.ClientSize.Width - 35);
        var content = new Panel { Location = new Point(0, 58), Size = new Size(width, 330), Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right, BackColor = Background };
        canvas.Controls.Add(content); canvas.Resize += (_, _) => { if (!content.IsDisposed) content.Width = Math.Max(500, canvas.ClientSize.Width - 35); };
        content.Controls.Add(new Label { Text = networkMore ? "Connection diagnostics" : "Connection", Location = new Point(0, 0), AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 15) });
        content.Controls.Add(new Label { Text = networkMore ? "Inspect the local gateway and DNS lookup from inside Zenn." : "Run in-app checks. These tests report your connection; they cannot change your ISP route or guarantee lower game ping.", Location = new Point(0, 31), AutoSize = true, ForeColor = SecondaryText });
        var col = Math.Max(250, (width - 14) / 2);
        if (!networkMore)
        {
            content.Controls.Add(SettingCard("Network latency", "Measure gateway and public endpoint response time.", true, 0, 67, col, key: "ping"));
            content.Controls.Add(SettingCard("Network speed", "Download a small 5 MB test file and estimate throughput.", false, col + 12, 67, col, key: "speed"));
            content.Controls.Add(SettingCard("Advanced", "Show additional connection maintenance actions.", networkAdvanced, 0, 157, col, v => { networkAdvanced = v; ShowOptimizerPage(5); }));
            if (networkAdvanced) content.Controls.Add(SettingCard("Clear DNS cache", "Clear only the local resolver cache; Windows repopulates it as needed.", false, col + 12, 157, col, key: "flushdns"));
        }
        else
        {
            content.Controls.Add(SettingCard("Default gateway", "Show the local router address reported by Windows.", true, 0, 67, col, key: "gateway"));
            content.Controls.Add(SettingCard("DNS lookup", "Resolve example.com using your current DNS configuration.", true, col + 12, 67, col, key: "dns"));
        }
        optimizeResult = new Label { Text = "Network check results will appear here after you press Apply.", Location = new Point(4, networkMore ? 157 : networkAdvanced ? 257 : 247), Size = new Size(width - 15, 70), ForeColor = SecondaryText, Font = new Font("Segoe UI", 9) };
        content.Controls.Add(optimizeResult);
        var footer = Footer(canvas, () => RunNetworkSelection(), () => { optimizeResult.Text = "Checks are read-only; no network setting has been changed."; }); footer.Location = new Point(0, 444); footer.Anchor = AnchorStyles.Bottom | AnchorStyles.Left | AnchorStyles.Right; canvas.Controls.Add(footer);
        canvas.Resize += (_, _) => footer.Location = new Point(0, Math.Max(444, canvas.ClientSize.Height - footer.Height - 4));
    }
    async Task RunNetworkSelection()
    {
        var output = new List<string>();
        if (stagedOptions.GetValueOrDefault("ping")?.Checked == true)
        {
            using var ping = new Ping();
            try { var reply = await ping.SendPingAsync("1.1.1.1", 2500); output.Add(reply.Status == IPStatus.Success ? $"Cloudflare response: {reply.RoundtripTime} ms" : "Cloudflare ping unavailable: " + reply.Status); }
            catch (Exception ex) { output.Add("Ping test unavailable: " + ex.Message); }
        }
        if (stagedOptions.GetValueOrDefault("speed")?.Checked == true)
        {
            try
            {
                using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(20) }; var sw = System.Diagnostics.Stopwatch.StartNew();
                var bytes = (await client.GetByteArrayAsync("https://speed.cloudflare.com/__down?bytes=5000000")).Length; sw.Stop();
                output.Add($"Approximate download: {bytes * 8d / sw.Elapsed.TotalSeconds / 1_000_000:0.0} Mbps (5 MB sample; results vary with server and connection load).");
            }
            catch (Exception ex) { output.Add("Speed test unavailable: " + ex.Message); }
        }
        if (stagedOptions.GetValueOrDefault("flushdns")?.Checked == true)
        {
            try { var p = Process.Start(new ProcessStartInfo("ipconfig.exe", "/flushdns") { UseShellExecute = false, RedirectStandardOutput = true, CreateNoWindow = true }); var text = p?.StandardOutput.ReadToEnd(); p?.WaitForExit(); output.Add(p?.ExitCode == 0 ? "Local DNS resolver cache cleared." : "DNS cache clear was not successful: " + text); }
            catch (Exception ex) { output.Add("DNS cache clear failed: " + ex.Message); }
        }
        if (stagedOptions.GetValueOrDefault("gateway")?.Checked == true)
        {
            var gateways = NetworkInterface.GetAllNetworkInterfaces().Where(n => n.OperationalStatus == OperationalStatus.Up).SelectMany(n => n.GetIPProperties().GatewayAddresses).Select(g => g.Address).Where(a => !a.Equals(IPAddress.Any) && !a.Equals(IPAddress.IPv6Any)).Distinct().ToArray();
            output.Add(gateways.Length == 0 ? "No active default gateway was reported." : "Default gateway: " + string.Join(", ", gateways.Select(a => a.ToString())));
        }
        if (stagedOptions.GetValueOrDefault("dns")?.Checked == true)
        {
            try { var ips = await System.Net.Dns.GetHostAddressesAsync("example.com"); output.Add("DNS lookup succeeded: " + string.Join(", ", ips.Take(3).Select(a => a.ToString()))); }
            catch (Exception ex) { output.Add("DNS lookup failed: " + ex.Message); }
        }
        if (output.Count == 0) output.Add("Turn on at least one connection check or maintenance action first.");
        optimizeResult.Text = string.Join("\r\n", output);
    }

    void BuildGameLibrary(Panel canvas)
    {
        stagedOptions.Clear();
        canvas.Controls.Add(SectionTab("🎮  Games", true, 0)); canvas.Controls.Add(SectionTab("···  And Many More", false, 125));
        canvas.Controls.Add(new Label { Text = "Games", Location = new Point(0, 58), AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 15) });
        canvas.Controls.Add(new Label { Text = "Choose a game to set its executable, view its profile, or launch it.", Location = new Point(0, 88), AutoSize = true, ForeColor = SecondaryText });
        var grid = new FlowLayoutPanel { Location = new Point(0, 124), Size = new Size(Math.Max(500, canvas.ClientSize.Width - 24), 640), Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right, AutoScroll = true, BackColor = Background, Padding = new Padding(0, 0, 4, 8), WrapContents = true };
        canvas.Controls.Add(grid); canvas.Resize += (_, _) => { if (!grid.IsDisposed) grid.Width = Math.Max(500, canvas.ClientSize.Width - 24); };
        foreach (var game in gameLibrary) grid.Controls.Add(GameTile(game));
    }
    Panel GameTile(string name)
    {
        var tile = new Panel { Size = new Size(246, 150), Margin = new Padding(0, 0, 12, 12), BackColor = Color.FromArgb(21, 23, 24), Cursor = Cursors.Hand, BorderStyle = BorderStyle.FixedSingle };
        var art = new Panel { Dock = DockStyle.Fill, BackColor = GameColor(name) };
        art.Paint += (_, e) =>
        {
            e.Graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            using var shade = new System.Drawing.Drawing2D.LinearGradientBrush(art.ClientRectangle, Color.FromArgb(0, 8, 9, 10), Color.FromArgb(235, 5, 6, 7), 90f); e.Graphics.FillRectangle(shade, art.ClientRectangle);
            using var ring = new Pen(Color.FromArgb(65, 235, 245, 240), 2); e.Graphics.DrawEllipse(ring, art.Width - 96, 14, 72, 72); e.Graphics.DrawEllipse(ring, art.Width - 76, 34, 32, 32);
            using var head = new Font("Segoe UI Black", 24, FontStyle.Bold); e.Graphics.DrawString(GameInitial(name), head, new SolidBrush(Color.FromArgb(185, Color.White)), new PointF(art.Width - 83, 25));
        };
        var exe = gamePaths.GetValueOrDefault(name); var ready = !string.IsNullOrWhiteSpace(exe) && File.Exists(exe);
        art.Controls.Add(new Label { Text = name, Location = new Point(14, 89), AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 12) });
        art.Controls.Add(new Label { Text = ready ? "READY  ·  OPEN GAME PROFILE  →" : "NOT SET UP  ·  CLICK TO CONFIGURE  →", Location = new Point(15, 119), AutoSize = true, ForeColor = ready ? Color.FromArgb(102, 231, 154) : Color.FromArgb(202, 205, 203), Font = new Font("Segoe UI Semibold", 7.5f) });
        tile.Controls.Add(art);
        void Open() => ShowGameProfile(name);
        tile.Click += (_, _) => Open(); foreach (Control c in art.Controls) c.Click += (_, _) => Open();
        return tile;
    }
    static Color GameColor(string name) => name switch
    {
        "Minecraft" => Color.FromArgb(57, 109, 62), "Fortnite" => Color.FromArgb(71, 56, 128), "Valorant" => Color.FromArgb(146, 55, 62), "Counter-Strike 2" => Color.FromArgb(149, 95, 44), "Apex Legends" => Color.FromArgb(145, 42, 83), "Overwatch 2" => Color.FromArgb(96, 119, 143), "GTA V" => Color.FromArgb(49, 117, 91), "League of Legends" => Color.FromArgb(44, 102, 137), "PUBG" => Color.FromArgb(127, 95, 51), _ => Color.FromArgb(43, 103, 143)
    };
    static string GameInitial(string name) => name switch { "Counter-Strike 2" => "CS", "Rocket League" => "RL", "League of Legends" => "LoL", _ => name[..Math.Min(2, name.Length)].ToUpperInvariant() };

    void ShowGameProfile(string name)
    {
        var index = Array.IndexOf(gameLibrary, name); if (index >= 0) selectedGameIndex = index;
        gameChoice.SelectedIndex = Math.Max(0, index);
        pageHost.Controls.Clear();
        var root = new Panel { Dock = DockStyle.Fill, BackColor = Background, Padding = new Padding(30, 22, 26, 20) }; pageHost.Controls.Add(root);
        var back = SecondaryButton("←  All Games", 140); back.Location = new Point(0, 0); back.Click += (_, _) => ShowOptimizerPage(7); root.Controls.Add(back);
        root.Controls.Add(new Label { Text = name, Location = new Point(0, 57), AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 24, FontStyle.Bold) });
        var path = gamePaths.GetValueOrDefault(name); var available = !string.IsNullOrWhiteSpace(path) && File.Exists(path);
        root.Controls.Add(new Label { Text = available ? "Game executable is ready. Zenn can apply a reversible Windows graphics preference." : "This game is in Zenn's library. Locate its executable to enable the profile and launch controls.", Location = new Point(2, 103), Size = new Size(760, 42), ForeColor = SecondaryText, Font = new Font("Segoe UI", 9.5f) });
        root.Controls.Add(new Label { Text = available ? "EXECUTABLE  ·  " + path : "EXECUTABLE  ·  Not selected", Location = new Point(2, 157), Size = new Size(790, 42), ForeColor = available ? Green : SecondaryText, Font = new Font("Segoe UI", 8.5f), AutoEllipsis = true });
        var locate = MakeGreenButton("LOCATE GAME", 158); locate.Location = new Point(0, 216); locate.Click += (_, _) => LocateGame(name); root.Controls.Add(locate);
        var launch = SecondaryButton("LAUNCH GAME", 158); launch.Location = new Point(172, 216); launch.Click += (_, _) => LaunchSelectedGame(); root.Controls.Add(launch);
        var profile = MakeGreenButton("APPLY GAME PROFILE", 210); profile.Location = new Point(344, 216); profile.Click += async (_, _) => { var msg = await ApplyGamingProfile(gamePaths.GetValueOrDefault(name)); MessageBox.Show(msg + "\nZenn can set a per-app high-performance GPU preference, Windows Game Mode, and a power plan. It does not claim to increase FPS in every game.", "Zenn game profile"); }; root.Controls.Add(profile);
        var monitor = SecondaryButton("LIVE MONITOR", 158); monitor.Location = new Point(0, 268); monitor.Click += (_, _) => ShowMonitor(); root.Controls.Add(monitor);
        var undo = SecondaryButton("REVERT WINDOWS PROFILE", 210); undo.Location = new Point(172, 268); undo.Click += (_, _) => UndoPC(); root.Controls.Add(undo);
        var note = new Panel { Location = new Point(0, 336), Size = new Size(720, 104), BackColor = Card, BorderStyle = BorderStyle.FixedSingle };
        note.Controls.Add(new Label { Text = "ABOUT THIS PROFILE", Location = new Point(16, 14), AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 10) });
        note.Controls.Add(new Label { Text = "Zenn applies a Windows graphics preference for this executable and can enable Windows Game Mode / a high-performance power plan. It leaves game files and competitive settings alone. Use Revert Windows Profile to restore the saved Windows settings.", Location = new Point(16, 42), Size = new Size(680, 48), ForeColor = SecondaryText, Font = new Font("Segoe UI", 8.5f) }); root.Controls.Add(note);
        status.Text = "ZENN  /  GAMES & APPS  /  " + name.ToUpperInvariant();
    }
    void LocateGame(string name)
    {
        using var dialog = new OpenFileDialog { Title = "Choose " + name + " executable", Filter = "Windows applications (*.exe)|*.exe", CheckFileExists = true };
        if (dialog.ShowDialog(this) != DialogResult.OK) return; gamePaths[name] = dialog.FileName; try { SaveGamePaths(); } catch (Exception ex) { MessageBox.Show("Game selected, but the path couldn't be saved: " + ex.Message, "Zenn"); }
        ShowGameProfile(name);
    }
}
