using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Text;
using System.Windows.Forms;

namespace BlockCare;

static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new Dashboard());
    }
}

partial class Dashboard : Form
{
    static readonly Color Background = Color.FromArgb(8, 9, 10);
    static readonly Color Sidebar = Color.FromArgb(15, 16, 17);
    static readonly Color Card = Color.FromArgb(22, 24, 25);
    static readonly Color Green = Color.FromArgb(19, 193, 99);
    static readonly Color BlueBolt = Color.FromArgb(68, 151, 255);
    static readonly Color SecondaryText = Color.FromArgb(165, 171, 169);

    readonly NotifyIcon tray = new();
    readonly System.Windows.Forms.Timer timer = new() { Interval = 3000 };
    readonly DataGridView grid = new()
    {
        Dock = DockStyle.Fill,
        ReadOnly = true,
        AllowUserToAddRows = false,
        SelectionMode = DataGridViewSelectionMode.FullRowSelect,
        MultiSelect = false,
        AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill
    };
    readonly Label status = new() { Dock = DockStyle.Fill, ForeColor = Green, BackColor = Sidebar, Padding = new Padding(18, 13, 0, 0) };
    readonly Button updateButton = new() { Dock = DockStyle.Fill, Text = "CHECKING UPDATES", BackColor = Green, ForeColor = Color.FromArgb(4, 17, 10), FlatStyle = FlatStyle.Flat, Font = new Font("Segoe UI Semibold", 8), Cursor = Cursors.Hand, Margin = new Padding(0, 7, 14, 7) };
    readonly Panel pageHost = new() { Dock = DockStyle.Fill, BackColor = Background };
    readonly Dictionary<int, (double Cpu, DateTime At)> previous = new();
    readonly List<Button> navButtons = new();
    readonly int[] primaryNavModules = { 0, 4, 5, 7 };
    ComboBox gameChoice = new();
    int selectedGameIndex;
    Label optimizeResult = new();
    bool quitting, busy;

    readonly string[] modules =
    {
        "Start", "Driver", "Bloatware", "Telemetry", "Latency", "Network",
        "Overclocking", "Games", "Miscellaneous", "Cleanup", "BIOS", "Experimental"
    };

    readonly string[] moduleDescriptions =
    {
        "Your PC at a glance and a reversible gaming tune-up.",
        "Review display drivers, Windows updates, and device status.",
        "Review startup and installed apps one at a time.",
        "Open Windows privacy controls and review diagnostics choices.",
        "Check local latency and use a game focus profile.",
        "Inspect your connection and open adapter settings.",
        "Review GPU readings and cooling before changing clocks.",
        "Choose a game and check its available performance settings.",
        "Browser, background apps, and additional controls.",
        "Review storage and Windows cleanup tools.",
        "Read BIOS information; firmware updates remain manual.",
        "Advanced notes and reversible options for experienced users."
    };

    public Dashboard()
    {
        Text = "Zenn";
        Icon = ZennIcon();
        Size = new Size(1220, 820);
        MinimumSize = new Size(1000, 680);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Background;
        ForeColor = Color.White;
        Font = new Font("Segoe UI", 9.5f);

        var shell = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, BackColor = Background, Margin = Padding.Empty };
        shell.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 224));
        shell.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        Controls.Add(shell);

        var nav = new Panel { Dock = DockStyle.Fill, BackColor = Sidebar, Padding = new Padding(14), AutoScroll = true };
        shell.Controls.Add(nav, 0, 0);
        BuildNavigation(nav);

        var body = new Panel { Dock = DockStyle.Fill, BackColor = Background, Padding = new Padding(0) };
        shell.Controls.Add(body, 1, 0);
        var bodyLayout = new TableLayoutPanel { Dock = DockStyle.Fill, ColumnCount = 2, RowCount = 2, BackColor = Background, Margin = Padding.Empty };
        bodyLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        bodyLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 170));
        bodyLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 52));
        bodyLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        body.Controls.Add(bodyLayout);
        status.Text = "ZENN  /  SYSTEM READY";
        status.Font = new Font("Segoe UI Semibold", 9);
        updateButton.FlatAppearance.BorderSize = 0;
        updateButton.Click += async (_, _) => { if (string.IsNullOrEmpty(updateInstallerUrl)) await CheckForUpdates(true); else await InstallAvailableUpdate(); };
        bodyLayout.Controls.Add(status, 0, 0);
        bodyLayout.Controls.Add(updateButton, 1, 0);
        bodyLayout.Controls.Add(pageHost, 0, 1);

        BuildMonitorGrid();
        gameChoice.DropDownStyle = ComboBoxStyle.DropDownList;
        gameChoice.Items.AddRange(new object[] { "Minecraft", "Fortnite", "Valorant", "Counter-Strike 2", "Apex Legends", "Overwatch 2", "GTA V", "League of Legends", "PUBG", "Rocket League" });
        gameChoice.SelectedIndex = 0;
        LoadGamePaths();
        ShowDashboard();

        tray.Icon = ZennIcon();
        tray.Text = "Zenn PC Optimizer";
        tray.Visible = true;
        var menu = new ContextMenuStrip();
        menu.Items.Add("Open Zenn", null, (_, _) => ShowWindow());
        menu.Items.Add("Pause / resume monitor", null, (_, _) => timer.Enabled = !timer.Enabled);
        menu.Items.Add("Exit", null, (_, _) => { quitting = true; Close(); });
        tray.ContextMenuStrip = menu;
        tray.DoubleClick += (_, _) => ShowWindow();

        FormClosing += (_, e) =>
        {
            if (!quitting) { e.Cancel = true; Hide(); }
            else { timer.Stop(); tray.Dispose(); }
        };
        timer.Tick += async (_, _) => { UpdateGameFocus(); await RefreshApps(); };
        Shown += async (_, _) => { await RefreshApps(); timer.Start(); await CheckForUpdates(false); };
        FindSound(false);
    }

    void BuildNavigation(Panel nav)
    {
        var brand = new FlowLayoutPanel { Location = new Point(8, 8), Size = new Size(185, 50), WrapContents = false, BackColor = Sidebar, Margin = Padding.Empty };
        var mark = new Panel { Size = new Size(32, 38), BackColor = Sidebar, Margin = new Padding(2, 3, 3, 0) };
        mark.Paint += (_, e) =>
        {
            e.Graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
            using var brush = new SolidBrush(BlueBolt);
            e.Graphics.FillPolygon(brush, new[] { new Point(21, 1), new Point(5, 21), new Point(14, 21), new Point(10, 36), new Point(28, 14), new Point(19, 14) });
        };
        brand.Controls.Add(mark);
        brand.Controls.Add(new Label { Text = "Zenn", AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 19, FontStyle.Bold), Margin = new Padding(0, 6, 0, 0) });
        nav.Controls.Add(brand);
        var caption = new Label { Text = "PC OPTIMIZER", Location = new Point(12, 56), Size = new Size(175, 20), ForeColor = SecondaryText, Font = new Font("Segoe UI", 8, FontStyle.Bold) };
        nav.Controls.Add(caption);
        var dashboard = new Button { Text = "←  BACK TO DASHBOARD", Location = new Point(10, 86), Size = new Size(184, 38), BackColor = Green, ForeColor = Color.FromArgb(4, 17, 10), FlatStyle = FlatStyle.Flat, Font = new Font("Segoe UI Semibold", 8.5f), Cursor = Cursors.Hand };
        dashboard.FlatAppearance.BorderSize = 0;
        dashboard.Click += (_, _) => { SelectNavigation(0); ShowDashboard(); };
        nav.Controls.Add(dashboard);

        for (var i = 0; i < primaryNavModules.Length; i++)
        {
            var index = primaryNavModules[i];
            var label = index switch { 0 => "⌂   Dashboard", 4 => "◉   Latency", 5 => "▥   Network", _ => "🎮  Games & Apps" };
            var button = new Button
            {
                Text = label,
                Tag = index,
                Location = new Point(10, 136 + i * 44),
                Size = new Size(184, 37),
                TextAlign = ContentAlignment.MiddleLeft,
                Padding = new Padding(10, 0, 0, 0),
                BackColor = Sidebar,
                ForeColor = Color.FromArgb(218, 222, 219),
                FlatStyle = FlatStyle.Flat,
                Font = new Font("Segoe UI", 9),
                Cursor = Cursors.Hand
            };
            button.FlatAppearance.BorderSize = 0;
            button.Click += (_, _) => { SelectNavigation(index); if (index == 0) ShowDashboard(); else ShowModule(index); };
            navButtons.Add(button);
            nav.Controls.Add(button);
        }
        var more = new Button { Text = "···   And Many More", Location = new Point(10, 136 + primaryNavModules.Length * 44), Size = new Size(184, 37), TextAlign = ContentAlignment.MiddleLeft, Padding = new Padding(10, 0, 0, 0), BackColor = Sidebar, ForeColor = SecondaryText, FlatStyle = FlatStyle.Flat, Font = new Font("Segoe UI", 9, FontStyle.Italic), Cursor = Cursors.Hand };
        more.FlatAppearance.BorderSize = 0;
        var moreMenu = new ContextMenuStrip();
        foreach (var index in Enumerable.Range(0, modules.Length).Where(x => !primaryNavModules.Contains(x)))
            moreMenu.Items.Add(modules[index], null, (_, _) => { SelectNavigation(index); ShowModule(index); });
        more.Click += (_, _) => moreMenu.Show(more, new Point(0, more.Height));
        nav.Controls.Add(more);
    }

    void SelectNavigation(int selected)
    {
        for (var i = 0; i < navButtons.Count; i++)
        {
            var active = Convert.ToInt32(navButtons[i].Tag) == selected;
            navButtons[i].BackColor = active ? Green : Sidebar;
            navButtons[i].ForeColor = active ? Color.White : Color.FromArgb(218, 222, 219);
        }
    }

    void ShowDashboard()
    {
        pageHost.Controls.Clear();
        var scroll = new Panel { Dock = DockStyle.Fill, AutoScroll = true, BackColor = Background, Padding = new Padding(30, 26, 28, 24) };
        pageHost.Controls.Add(scroll);
        var title = new Label { Text = "Welcome to Zenn", Location = new Point(30, 24), AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 25, FontStyle.Bold) };
        scroll.Controls.Add(title);
        var subtitle = new Label { Text = "Choose a module to tune and understand your PC.", Location = new Point(33, 68), AutoSize = true, ForeColor = SecondaryText, Font = new Font("Segoe UI", 10) };
        scroll.Controls.Add(subtitle);

        var spec = new Label { Text = "YOUR PC  •  Intel Core i5-8400   |   NVIDIA GTX 1060 6 GB   |   16 GB RAM   |   1080p / 144 Hz", Location = new Point(33, 110), AutoSize = true, ForeColor = Green, Font = new Font("Segoe UI Semibold", 9) };
        scroll.Controls.Add(spec);
        var gamesTitle = new Label { Text = "YOUR GAMES", Location = new Point(33, 153), AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 13) };
        scroll.Controls.Add(gamesTitle);
        var games = new TableLayoutPanel { Location = new Point(26, 184), Size = new Size(Math.Max(650, scroll.ClientSize.Width - 68), 132), ColumnCount = 3, RowCount = 1, BackColor = Background, Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
        for (var c = 0; c < 3; c++) games.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.333f));
        var gameNames = new[] { "Fortnite", "Minecraft", "Rocket League" };
        for (var i = 0; i < gameNames.Length; i++)
        {
            var index = i;
            var card = MakeGameCard(i, gameNames[i]);
            void OpenGame() { selectedGameIndex = Array.IndexOf(gameLibrary, gameNames[index]); SelectNavigation(7); ShowGameProfile(gameNames[index]); }
            void AttachClick(Control control) { control.Click += (_, _) => OpenGame(); foreach (Control child in control.Controls) AttachClick(child); }
            AttachClick(card);
            games.Controls.Add(card, i, 0);
        }
        scroll.Controls.Add(games);
        scroll.Resize += (_, _) => { if (!scroll.IsDisposed && scroll.Controls.Contains(games)) games.Width = Math.Max(650, scroll.ClientSize.Width - 68); };

        var section = new Label { Text = "OPTIMIZER MODULES", Location = new Point(33, 345), AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 12) };
        scroll.Controls.Add(section);
        var cards = new TableLayoutPanel { Location = new Point(30, 376), Size = new Size(Math.Max(650, scroll.ClientSize.Width - 74), 420), ColumnCount = 3, RowCount = 4, BackColor = Background, Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right };
        for (var c = 0; c < 3; c++) cards.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.333f));
        for (var r = 0; r < 4; r++) cards.RowStyles.Add(new RowStyle(SizeType.Percent, 25));
        for (var i = 0; i < modules.Length; i++)
        {
            var index = i;
            var card = MakeModuleCard(i, modules[i], moduleDescriptions[i]);
            card.Click += (_, _) => { SelectNavigation(index); ShowModule(index); };
            foreach (Control child in card.Controls) child.Click += (_, _) => { SelectNavigation(index); ShowModule(index); };
            cards.Controls.Add(card, i % 3, i / 3);
        }
        scroll.Controls.Add(cards);
        scroll.Resize += (_, _) => { if (!scroll.IsDisposed && scroll.Controls.Contains(cards)) cards.Width = Math.Max(650, scroll.ClientSize.Width - 74); };
        status.Text = "ZENN  /  DASHBOARD";
    }

    Panel MakeGameCard(int index, string name)
    {
        var installed = !string.IsNullOrWhiteSpace(gamePaths.GetValueOrDefault(name)) && File.Exists(gamePaths[name]);
        var panel = new Panel { Dock = DockStyle.Fill, Margin = new Padding(7), BackColor = Card, Cursor = Cursors.Hand, BorderStyle = BorderStyle.FixedSingle };
        panel.Controls.Add(new Panel { Dock = DockStyle.Top, Height = 3, BackColor = Green });
        panel.Controls.Add(new Label { Text = name, Location = new Point(16, 13), AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 12) });
        var badge = new Panel { Location = new Point(15, 49), Size = new Size(40, 40), BackColor = Color.FromArgb(31, 34, 32) };
        badge.Controls.Add(new Label { Text = name == "Fortnite" ? "F" : name == "Minecraft" ? "M" : "RL", Dock = DockStyle.Fill, TextAlign = ContentAlignment.MiddleCenter, ForeColor = Green, Font = new Font("Segoe UI Semibold", 13, FontStyle.Bold) });
        panel.Controls.Add(badge);
        panel.Controls.Add(new Label { Text = installed ? "READY  ·  CLICK TO OPEN" : "SET UP  ·  CLICK TO LOCATE", Location = new Point(67, 53), AutoSize = true, ForeColor = installed ? Green : SecondaryText, Font = new Font("Segoe UI Semibold", 8) });
        panel.Controls.Add(new Label { Text = installed ? "Game profile and launch controls" : "Choose the game's .exe to enable controls", Location = new Point(67, 75), AutoSize = true, ForeColor = SecondaryText, Font = new Font("Segoe UI", 8) });
        AnimateCard(panel);
        return panel;
    }

    void AnimateCard(Panel card)
    {
        var normal = Card; var hover = Color.FromArgb(31, 38, 34);
        System.Windows.Forms.Timer? animation = null;
        void Start(Color target)
        {
            animation?.Stop(); animation?.Dispose();
            var from = card.BackColor; var frame = 0; animation = new System.Windows.Forms.Timer { Interval = 16 };
            animation.Tick += (_, _) =>
            {
                frame++; var t = Math.Min(1f, frame / 8f);
                card.BackColor = Color.FromArgb((int)(from.R + (target.R - from.R) * t), (int)(from.G + (target.G - from.G) * t), (int)(from.B + (target.B - from.B) * t));
                if (frame >= 8) { animation.Stop(); animation.Dispose(); animation = null; }
            };
            animation.Start();
        }
        card.MouseEnter += (_, _) => Start(hover);
        card.MouseLeave += (_, _) => Start(normal);
        foreach (Control child in card.Controls) { child.MouseEnter += (_, _) => Start(hover); child.MouseLeave += (_, _) => Start(normal); }
    }

    Panel MakeModuleCard(int index, string title, string description)
    {
        var panel = new Panel { Dock = DockStyle.Fill, Margin = new Padding(7), BackColor = Card, Cursor = Cursors.Hand, BorderStyle = BorderStyle.FixedSingle };
        panel.Controls.Add(new Panel { Dock = DockStyle.Top, Height = 3, BackColor = Green });
        panel.Controls.Add(new Label { Text = $"{index + 1:00}", Location = new Point(15, 18), AutoSize = true, ForeColor = Green, Font = new Font("Segoe UI Semibold", 11) });
        panel.Controls.Add(new Label { Text = title, Location = new Point(56, 18), AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 11) });
        panel.Controls.Add(new Label { Text = description, Location = new Point(15, 52), Size = new Size(300, 44), ForeColor = SecondaryText, Font = new Font("Segoe UI", 8.5f) });
        AnimateCard(panel);
        return panel;
    }

    void ShowModule(int index)
    {
        if (index is 4 or 5 or 7) { ShowOptimizerPage(index); return; }
        pageHost.Controls.Clear();
        var scroll = new Panel { Dock = DockStyle.Fill, AutoScroll = true, BackColor = Background };
        pageHost.Controls.Add(scroll);
        var title = new Label { Text = $"{index + 1:00}  /  {modules[index]}", Location = new Point(30, 22), AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 22, FontStyle.Bold) };
        scroll.Controls.Add(title);
        var subtitle = new Label { Text = moduleDescriptions[index], Location = new Point(33, 62), AutoSize = true, ForeColor = SecondaryText, Font = new Font("Segoe UI", 9.5f) };
        scroll.Controls.Add(subtitle);
        var cards = new FlowLayoutPanel { Location = new Point(24, 102), Size = new Size(Math.Max(650, scroll.ClientSize.Width - 48), 510), Anchor = AnchorStyles.Top | AnchorStyles.Left | AnchorStyles.Right, WrapContents = true, AutoScroll = true, BackColor = Background, Padding = new Padding(0, 0, 8, 8) };
        scroll.Controls.Add(cards);
        scroll.Resize += (_, _) => cards.Width = Math.Max(650, scroll.ClientSize.Width - 48);
        optimizeResult = new Label { Text = "Tune-up results will appear here.", Location = new Point(33, 625), AutoSize = true, MaximumSize = new Size(900, 0), ForeColor = SecondaryText, Font = new Font("Segoe UI", 9) };
        if (index is 0 or 7) scroll.Controls.Add(optimizeResult);
        else optimizeResult.Visible = false;
        BuildModuleCards(index, cards);
        status.Text = $"ZENN  /  {modules[index].ToUpperInvariant()}";
    }

    void BuildModuleCards(int index, FlowLayoutPanel cards)
    {
        switch (index)
        {
            case 0:
                AddActionCard(cards, "Safe gaming tune-up", "Enable Windows Game Mode and a reversible power profile, then inspect the selected game's settings.", "OPTIMIZE MY PC", async () => await OptimizeFPS());
                AddActionCard(cards, "Undo previous tune-up", "Restore the saved Windows and power settings from the last Zenn tune-up.", "UNDO CHANGES", () => UndoPC());
                AddActionCard(cards, "Hardware snapshot", "Read the detected GPU and Windows storage details. No driver is changed.", "CHECK HARDWARE", async () => await Hardware());
                AddActionCard(cards, "Live process monitor", "See current process CPU and memory use; identify spikes while a stutter happens.", "OPEN MONITOR", () => ShowMonitor());
                break;
            case 1:
                AddActionCard(cards, "GPU and storage", "Read the NVIDIA snapshot and Windows storage health/media information.", "CHECK HARDWARE", async () => await Hardware());
                AddActionCard(cards, "Windows Update", "Review updates and optional driver offers through Windows Settings.", "OPEN WINDOWS UPDATE", () => Open("ms-settings:windowsupdate"));
                AddActionCard(cards, "Device Manager", "Inspect devices and driver status. Zenn does not remove or force-install drivers.", "OPEN DEVICE MANAGER", () => Open("devmgmt.msc"));
                AddActionCard(cards, "Driver cleanup", "Use driver cleanup only when a driver install is broken or the problem began after a driver change.", "READ THE NOTE", () => MessageBox.Show("Zenn does not run DDU automatically. First compare the stutter with Task Manager and check whether it began after a driver update.", "Driver note"));
                break;
            case 2:
                AddActionCard(cards, "Startup apps", "Turn off only the apps you recognize and do not need at sign-in.", "REVIEW STARTUP APPS", () => Open("ms-settings:startupapps"));
                AddActionCard(cards, "Installed apps", "Review installed programs in Windows Settings before removing anything.", "REVIEW INSTALLED APPS", () => Open("ms-settings:appsfeatures"));
                AddActionCard(cards, "Background usage", "Use the live monitor to find the app that is actually using resources.", "OPEN MONITOR", () => ShowMonitor());
                break;
            case 3:
                AddActionCard(cards, "Windows privacy", "Open privacy and diagnostics settings so you can review each option.", "OPEN PRIVACY SETTINGS", () => Open("ms-settings:privacy"));
                AddActionCard(cards, "Diagnostic data", "Review the Windows diagnostic-data controls. Zenn does not disable system services.", "OPEN DIAGNOSTICS", () => Open("ms-settings:privacy-feedback"));
                AddActionCard(cards, "Manual controls", "Privacy changes can affect Windows features. Review the description in Settings before changing them.", "OPEN SETTINGS", () => Open("ms-settings:privacy"));
                break;
            case 4:
                AddActionCard(cards, "Latency check", "Check the wired route to your gateway and a public endpoint; ICMP can be filtered.", "CHECK LATENCY", async () => await OptimizeLatency());
                AddActionCard(cards, "Game Focus", "Temporarily lower selected background apps' CPU priority while a supported game runs.", "OPEN APP CONTROLS", () => { SelectNavigation(8); ShowModule(8); });
                AddActionCard(cards, "Windows Game Mode", "Open Windows Game Mode settings. The Start tune-up can enable it with an undo backup.", "OPEN GAME MODE", () => Open("ms-settings:gaming-gamemode"));
                break;
            case 5:
                AddActionCard(cards, "Ethernet check", "Test the local gateway and a public IP. Results do not guarantee the game server route.", "RUN CONNECTION CHECK", async () => await Network());
                AddActionCard(cards, "Network status", "Review Windows network status and adapter settings.", "OPEN NETWORK SETTINGS", () => Open("ms-settings:network-status"));
                AddActionCard(cards, "Adapter options", "Open the classic network-adapter list for inspection.", "OPEN ADAPTERS", () => Open("ncpa.cpl"));
                break;
            case 6:
                AddActionCard(cards, "GPU readings", "Read utilization, memory, and temperature when the NVIDIA command-line tool is available.", "CHECK GPU", async () => await Hardware());
                AddActionCard(cards, "Cooling check", "Watch temperatures and clock speeds under load. The best clock depends on cooling and card design.", "OPEN MONITOR", () => ShowMonitor());
                AddActionCard(cards, "Manual overclocking", "Zenn will not apply an overclock automatically. Unstable clocks can cause crashes, heat, or more stutter.", "VIEW NOTE", () => MessageBox.Show("This screen is informational. We will not apply voltage, clock, or BIOS changes automatically.", "Overclocking note"));
                break;
            case 7:
                AddGameControls(cards);
                break;
            case 8:
                InstallFeatures(cards);
                AddActionCard(cards, "Export process list", "Save a CSV snapshot of the current monitor rows.", "EXPORT CSV", () => Export());
                AddActionCard(cards, "Verify selected app", "Check the digital signature of the selected process in the live monitor.", "CHECK SIGNATURE", async () => await Signature());
                break;
            case 9:
                AddActionCard(cards, "Storage settings", "Review free space and Windows Storage Sense options.", "OPEN STORAGE", () => Open("ms-settings:storagesense"));
                AddActionCard(cards, "Temporary files", "Choose temporary files in Windows Settings before removing them.", "REVIEW TEMP FILES", () => Open("ms-settings:temporaryfiles"));
                AddActionCard(cards, "Recent system errors", "Read recent System log errors as clues, not a diagnosis.", "SHOW RECENT ERRORS", async () => await Errors());
                break;
            case 10:
                AddActionCard(cards, "System information", "Read BIOS version, baseboard, Windows build, and installed memory.", "OPEN SYSTEM INFO", () => Open("msinfo32.exe"));
                AddActionCard(cards, "Firmware updates", "Use HP's official support page for your exact model. Do not flash BIOS files from other models.", "OPEN HP SUPPORT", () => Open("https://support.hp.com/drivers"));
                AddActionCard(cards, "No automatic BIOS changes", "BIOS updates can make a PC unusable if interrupted or mismatched. Zenn leaves firmware alone.", "READ NOTE", () => MessageBox.Show("Check the exact product number and read HP's instructions before considering a BIOS update.", "BIOS note"));
                break;
            case 11:
                AddActionCard(cards, "Undo saved changes", "Restore the most recent Zenn power and Game Mode backup.", "UNDO CHANGES", () => UndoPC());
                AddActionCard(cards, "Create a process report", "Review the live monitor and export its rows as CSV.", "OPEN MONITOR", () => ShowMonitor());
                AddActionCard(cards, "Experimental changes", "No undocumented registry bundles, service-disabling scripts, or forced driver changes are included.", "OPEN PRIVACY SETTINGS", () => Open("ms-settings:privacy"));
                break;
        }
    }

    void AddGameControls(FlowLayoutPanel cards)
    {
        var panel = new Panel { Width = 670, Height = 268, BackColor = Card, Margin = new Padding(8), BorderStyle = BorderStyle.FixedSingle };
        panel.Controls.Add(new Panel { Dock = DockStyle.Top, Height = 3, BackColor = Green });
        panel.Controls.Add(new Label { Text = "GAME PROFILE", Location = new Point(18, 16), AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 12) });
        panel.Controls.Add(new Label { Text = "Choose a game, set its executable once, then apply Zenn's reversible Windows profile or launch it.", Location = new Point(18, 48), AutoSize = true, ForeColor = SecondaryText, Font = new Font("Segoe UI", 9) });
        gameChoice = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 250, Font = new Font("Segoe UI", 10), BackColor = Card, ForeColor = Color.White, Location = new Point(18, 83) };
        gameChoice.SelectedIndex = Math.Clamp(selectedGameIndex, 0, gameChoice.Items.Count - 1);
        gameChoice.SelectedIndexChanged += (_, _) => { selectedGameIndex = gameChoice.SelectedIndex; RefreshGamePathLabel(); };
        panel.Controls.Add(gameChoice);
        gamePathLabel = new Label { Location = new Point(18, 119), Size = new Size(620, 25), AutoEllipsis = true, ForeColor = SecondaryText, Font = new Font("Segoe UI", 8.5f) };
        panel.Controls.Add(gamePathLabel); RefreshGamePathLabel();
        var optimize = MakeGreenButton("APPLY GAME PROFILE", 210); optimize.Location = new Point(18, 157); optimize.Click += async (_, _) => await OptimizeFPS(); panel.Controls.Add(optimize);
        var launch = MakeGreenButton("LAUNCH GAME", 145); launch.Location = new Point(18, 207); launch.Click += (_, _) => LaunchSelectedGame(); panel.Controls.Add(launch);
        var locate = MakeGreenButton("LOCATE GAME", 145); locate.Location = new Point(176, 207); locate.Click += (_, _) => LocateSelectedGame(); panel.Controls.Add(locate);
        cards.Controls.Add(panel);
        AddActionCard(cards, "Live game monitor", "See process CPU and memory use while the game runs.", "OPEN MONITOR", () => ShowMonitor());
        AddActionCard(cards, "Undo Windows profile", "Restore the saved power plan, Game Mode, and graphics preference.", "UNDO CHANGES", () => UndoPC());
        AddActionCard(cards, "Game settings", "Inspect frame limits and V-Sync for Fortnite; other game files are left untouched.", "CHECK GAME SETTINGS", async () => await OptimizeFPS());
    }

    void AddActionCard(FlowLayoutPanel parent, string title, string description, string buttonText, Action action)
    {
        var card = MakeActionCard(title, description);
        var button = MakeGreenButton(buttonText, 240);
        button.Location = new Point(18, 128);
        button.Click += (_, _) => { try { PlayClick(); action(); } catch (Exception ex) { MessageBox.Show(ex.Message, "Zenn"); } };
        card.Controls.Add(button);
        parent.Controls.Add(card);
    }

    Panel MakeActionCard(string title, string description)
    {
        var p = new Panel { Width = 320, Height = 188, BackColor = Card, Margin = new Padding(8), BorderStyle = BorderStyle.FixedSingle };
        p.Controls.Add(new Panel { Dock = DockStyle.Top, Height = 3, BackColor = Green });
        p.Controls.Add(new Label { Text = title, Location = new Point(17, 18), AutoSize = true, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 11) });
        p.Controls.Add(new Label { Text = description, Location = new Point(18, 49), Size = new Size(276, 67), ForeColor = SecondaryText, Font = new Font("Segoe UI", 8.5f) });
        AnimateCard(p);
        return p;
    }

    Button MakeGreenButton(string text, int width)
    {
        var b = new Button { Text = text, Width = width, Height = 38, BackColor = Green, ForeColor = Color.FromArgb(4, 17, 10), FlatStyle = FlatStyle.Flat, Font = new Font("Segoe UI Semibold", 8.5f), Cursor = Cursors.Hand };
        b.FlatAppearance.BorderSize = 0;
        return b;
    }

    void BuildMonitorGrid()
    {
        foreach (var name in new[] { "PID", "APP", "CPU %", "RAM MB", "PUBLISHER", "WHAT IT MAY BE" }) grid.Columns.Add(name, name);
        grid.BackgroundColor = Color.FromArgb(13, 14, 15);
        grid.GridColor = Color.FromArgb(42, 45, 44);
        grid.DefaultCellStyle.BackColor = Color.FromArgb(13, 14, 15);
        grid.DefaultCellStyle.ForeColor = Color.FromArgb(222, 231, 224);
        grid.DefaultCellStyle.SelectionBackColor = Color.FromArgb(22, 77, 47);
        grid.ColumnHeadersDefaultCellStyle.BackColor = Color.FromArgb(24, 28, 25);
        grid.ColumnHeadersDefaultCellStyle.ForeColor = Green;
        grid.EnableHeadersVisualStyles = false;
        grid.RowHeadersVisible = false;
    }

    void ShowMonitor()
    {
        pageHost.Controls.Clear();
        var layout = new TableLayoutPanel { Dock = DockStyle.Fill, BackColor = Background, Padding = new Padding(22), ColumnCount = 1, RowCount = 3 };
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 52));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 52));
        pageHost.Controls.Add(layout);
        layout.Controls.Add(new Label { Text = "LIVE PC MONITOR", Dock = DockStyle.Fill, ForeColor = Color.White, Font = new Font("Segoe UI Semibold", 19), Padding = new Padding(3, 2, 0, 0) }, 0, 0);
        layout.Controls.Add(grid, 0, 1);
        var actions = new FlowLayoutPanel { Dock = DockStyle.Fill, WrapContents = false, Padding = new Padding(0, 5, 0, 0) };
        layout.Controls.Add(actions, 0, 2);
        Add(actions, "Explain selected", () => Explain());
        Add(actions, "Ask app to close", () => CloseApp());
        Add(actions, "Task Manager", () => Open("taskmgr.exe"));
        status.Text = "ZENN  /  LIVE MONITOR";
        _ = RefreshApps();
    }

    void Add(Control parent, string text, Action action)
    {
        var b = MakeGreenButton(text, 170);
        b.AutoSize = true;
        b.MinimumSize = new Size(136, 38);
        b.Click += (_, _) => { try { PlayClick(); action(); } catch (Exception ex) { MessageBox.Show(ex.Message, "Zenn"); } };
        parent.Controls.Add(b);
    }

    void ShowWindow() { Show(); WindowState = FormWindowState.Normal; Activate(); }
    static void Open(string file) => Process.Start(new ProcessStartInfo(file) { UseShellExecute = true });

    async Task RefreshApps()
    {
        if (busy) return;
        busy = true;
        try
        {
            var rows = await Task.Run(() =>
            {
                var result = new List<object[]>();
                var now = DateTime.UtcNow;
                var next = new Dictionary<int, (double Cpu, DateTime At)>();
                foreach (var p in Process.GetProcesses()) using (p)
                {
                    try
                    {
                        var cpu = p.TotalProcessorTime.TotalSeconds;
                        double usage = 0;
                        if (previous.TryGetValue(p.Id, out var old)) usage = Math.Max(0, (cpu - old.Cpu) / Math.Max(.1, (now - old.At).TotalSeconds) / Environment.ProcessorCount * 100);
                        next[p.Id] = (cpu, now);
                        string company = "Unavailable";
                        try { company = p.MainModule?.FileVersionInfo.CompanyName ?? "Unknown"; } catch { }
                        result.Add(new object[] { p.Id, p.ProcessName, Math.Round(usage, 1), Math.Round(p.WorkingSet64 / 1048576d), company, Describe(p.ProcessName) });
                    }
                    catch { }
                }
                previous.Clear(); foreach (var item in next) previous[item.Key] = item.Value;
                return result.OrderByDescending(x => (double)x[2]).ToList();
            });
            int? selected = grid.SelectedRows.Count > 0 ? Convert.ToInt32(grid.SelectedRows[0].Cells[0].Value) : null;
            grid.Rows.Clear();
            foreach (var row in rows)
            {
                var i = grid.Rows.Add(row);
                if (selected != null && (int)row[0] == selected) grid.Rows[i].Selected = true;
            }
            status.Text = $"ZENN  /  LIVE MONITOR     {rows.Count} PROCESSES     UPDATED {DateTime.Now.ToLongTimeString()}";
        }
        catch (Exception ex) { status.Text = "Zenn monitor: " + ex.Message; }
        finally { busy = false; }
    }

    static string Describe(string n) => n.ToLowerInvariant() switch
    {
        "chrome" => "Browser; tabs and extensions can use separate processes.",
        "msedge" => "Browser or embedded web content.",
        "discord" => "Voice/chat; closing disconnects your calls.",
        "javaw" or "java" => "Java app; often Minecraft, but confirm the window.",
        "fortniteclient-win64-shipping" => "Fortnite game process.",
        "rocketleague" => "Rocket League game process.",
        "svchost" or "lsass" or "csrss" or "wininit" or "services" or "system" => "Windows component name. Leave running; name alone does not verify identity.",
        "msmpeng" => "Microsoft Defender scanning process; leave protection enabled.",
        "nvcontainer" => "NVIDIA component name; may support graphics features.",
        _ => "Inspect publisher and file location before deciding."
    };

    Process? Selected() { if (grid.SelectedRows.Count == 0) return null; return Process.GetProcessById(Convert.ToInt32(grid.SelectedRows[0].Cells[0].Value)); }
    void Explain() { using var p = Selected(); if (p == null) return; string path = "Access restricted"; try { path = p.MainModule?.FileName ?? path; } catch { } MessageBox.Show(Describe(p.ProcessName) + "\n\nFile: " + path + "\n\nA publisher/name is not proof of safety. Use Windows Security to scan suspicious files.", "About " + p.ProcessName); }
    void CloseApp() { using var p = Selected(); if (p == null) return; if (p.Id == Environment.ProcessId || p.MainWindowHandle == IntPtr.Zero) { MessageBox.Show("Only apps with a visible window can be closed here. Background services are protected from this action."); return; } if (MessageBox.Show("Ask " + p.ProcessName + " to close? Save your work first.", "Close app", MessageBoxButtons.YesNo) == DialogResult.Yes) { if (!p.CloseMainWindow()) MessageBox.Show("The app did not accept the close request."); } }

    async Task Network()
    {
        var nic = NetworkInterface.GetAllNetworkInterfaces().FirstOrDefault(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType == NetworkInterfaceType.Ethernet && n.GetIPProperties().GatewayAddresses.Any());
        if (nic == null) { MessageBox.Show("No active Ethernet gateway found."); return; }
        var gateway = nic.GetIPProperties().GatewayAddresses.First().Address.ToString();
        status.Text = "Checking Ethernet latency — about 20 seconds";
        var report = new StringBuilder();
        foreach (var host in new[] { gateway, "1.1.1.1" })
        {
            var times = new List<long>(); using var ping = new Ping();
            for (int i = 0; i < 10; i++) { try { var r = await ping.SendPingAsync(host, 800); if (r.Status == IPStatus.Success) times.Add(r.RoundtripTime); } catch { } await Task.Delay(200); }
            report.AppendLine(host + ": " + (10 - times.Count) * 10 + "% unanswered ICMP probes; " + (times.Count > 0 ? times.Average().ToString("0.0") + " ms average" : "no replies"));
        }
        MessageBox.Show(report + "\nICMP may be filtered or deprioritized. This is not proof of game packet loss and does not measure your game server route.", "Ethernet check");
    }

    void Export()
    {
        using var dialog = new SaveFileDialog { Filter = "CSV report|*.csv", FileName = "Zenn-usage.csv" };
        if (dialog.ShowDialog() != DialogResult.OK) return;
        string Q(object? s) => "\"" + Convert.ToString(s)?.Replace("\"", "\"\"") + "\"";
        var lines = new List<string> { string.Join(",", grid.Columns.Cast<DataGridViewColumn>().Select(c => Q(c.HeaderText))) };
        lines.AddRange(grid.Rows.Cast<DataGridViewRow>().Select(r => string.Join(",", r.Cells.Cast<DataGridViewCell>().Select(c => Q(c.Value)))));
        File.WriteAllLines(dialog.FileName, lines);
    }
}
