using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using System.Text;
using System.Windows.Forms;
namespace BlockCare;
static class Program {
 [STAThread] static void Main(){ApplicationConfiguration.Initialize(); Application.Run(new Dashboard());}
}
partial class Dashboard:Form {
 NotifyIcon tray=new(); System.Windows.Forms.Timer timer=new(){Interval=3000};
 DataGridView grid=new(){Dock=DockStyle.Fill,ReadOnly=true,AllowUserToAddRows=false,SelectionMode=DataGridViewSelectionMode.FullRowSelect,MultiSelect=false,AutoSizeColumnsMode=DataGridViewAutoSizeColumnsMode.Fill};
 Label status=new(){Dock=DockStyle.Top,Height=65,Padding=new Padding(16),ForeColor=Color.LightGreen};\n ComboBox gameChoice=null!; Label optimizeResult=null!;
 Dictionary<int,(double cpu,DateTime at)> previous=new(); bool quitting,busy;
 public Dashboard(){
 Text="BlockCare";Size=new Size(1180,780);MinimumSize=new Size(960,650);BackColor=Color.FromArgb(7,12,24);ForeColor=Color.White;Font=new Font("Segoe UI",10);
 var shell=new TableLayoutPanel(){Dock=DockStyle.Fill,ColumnCount=2,BackColor=BackColor};shell.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,205));shell.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));Controls.Add(shell);
 var nav=new FlowLayoutPanel(){Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,BackColor=Color.FromArgb(10,17,32),Padding=new Padding(12),AutoScroll=true};shell.Controls.Add(nav,0,0);
 nav.Controls.Add(new Label(){Text="BLOCKCARE",AutoSize=true,ForeColor=Color.FromArgb(80,195,255),Font=new Font("Segoe UI Semibold",17),Margin=new Padding(8,14,8,4)});
 nav.Controls.Add(new Label(){Text="PC PERFORMANCE",AutoSize=true,ForeColor=Color.FromArgb(134,153,184),Font=new Font("Segoe UI",8),Margin=new Padding(9,0,8,24)});
 var body=new Panel(){Dock=DockStyle.Fill,BackColor=BackColor};shell.Controls.Add(body,1,0);
 status.Text="READY  •  monitoring refreshes every 3 seconds";status.Dock=DockStyle.Top;status.Height=45;status.Padding=new Padding(20,13,0,0);status.ForeColor=Color.FromArgb(115,202,255);body.Controls.Add(status);
 var tabs=new TabControl(){Dock=DockStyle.Fill,Appearance=TabAppearance.FlatButtons,SizeMode=TabSizeMode.Fixed,ItemSize=new Size(0,1)};body.Controls.Add(tabs);tabs.BringToFront();
 var home=new TabPage("OPTIMIZE"){BackColor=BackColor};var monitor=new TabPage("MONITOR"){BackColor=BackColor};var settings=new TabPage("SETTINGS"){BackColor=BackColor};tabs.TabPages.Add(home);tabs.TabPages.Add(monitor);tabs.TabPages.Add(settings);
 var links=new[]{"OPTIMIZE","MONITOR","SETTINGS"};var navButtons=new List<Button>();
 for(int i=0;i<links.Length;i++){int index=i;var b=new Button(){Text=links[i],Width=176,Height=44,TextAlign=ContentAlignment.MiddleLeft,FlatStyle=FlatStyle.Flat,BackColor=i==0?Color.FromArgb(20,78,120):Color.FromArgb(15,26,45),ForeColor=Color.White,Padding=new Padding(12,0,0,0),Margin=new Padding(2,4,2,4),Font=new Font("Segoe UI Semibold",9)};b.FlatAppearance.BorderSize=0;b.Click+=(_,_)=>{tabs.SelectedIndex=index;for(int n=0;n<navButtons.Count;n++)navButtons[n].BackColor=n==index?Color.FromArgb(20,78,120):Color.FromArgb(15,26,45);};navButtons.Add(b);nav.Controls.Add(b);}
 var homeLayout=new FlowLayoutPanel(){Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoScroll=true,Padding=new Padding(22),BackColor=BackColor};home.Controls.Add(homeLayout);
 homeLayout.Controls.Add(new Label(){Text="Your PC, ready for games",AutoSize=true,ForeColor=Color.White,Font=new Font("Segoe UI Semibold",23),Margin=new Padding(2,0,0,3)});
 homeLayout.Controls.Add(new Label(){Text="Pick a game, run one safe tune-up, then check what may be causing drops.",AutoSize=true,ForeColor=Color.FromArgb(155,172,199),Font=new Font("Segoe UI",10),Margin=new Padding(3,0,0,15)});
 var selector=new FlowLayoutPanel(){Width=850,Height=48,WrapContents=false,Margin=new Padding(0,0,0,10)};selector.Controls.Add(new Label(){Text="GAME",AutoSize=true,ForeColor=Color.FromArgb(122,194,255),Font=new Font("Segoe UI Semibold",9),Margin=new Padding(3,10,12,0)});gameChoice=new ComboBox(){DropDownStyle=ComboBoxStyle.DropDownList,Width=240,Font=new Font("Segoe UI",10),BackColor=Color.FromArgb(18,29,49),ForeColor=Color.White};gameChoice.Items.AddRange(new object[]{"Fortnite","Rocket League","Minecraft"});gameChoice.SelectedIndex=0;selector.Controls.Add(gameChoice);homeLayout.Controls.Add(selector);
 var cards=new TableLayoutPanel(){Width=880,Height=276,ColumnCount=2,RowCount=1,Margin=new Padding(0,0,0,12)};cards.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,50));cards.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,50));homeLayout.Controls.Add(cards);
 var fpsCard=MakeCard("FPS PERFORMANCE","Reduce avoidable Windows overhead and inspect the selected game's common FPS limits.",Color.FromArgb(36,139,255),"⚡");var lagCard=MakeCard("LATENCY & CONNECTION","Check your Ethernet path and apply the same low-overhead gaming profile.",Color.FromArgb(156,104,255),"◉");cards.Controls.Add(fpsCard,0,0);cards.Controls.Add(lagCard,1,0);
 var fpsButton=new Button(){Text="OPTIMIZE MY FPS",Width=260,Height=48,BackColor=Color.FromArgb(26,111,205),ForeColor=Color.White,FlatStyle=FlatStyle.Flat,Font=new Font("Segoe UI Semibold",10),Margin=new Padding(16,12,4,4)};fpsButton.FlatAppearance.BorderSize=0;fpsCard.Controls.Add(fpsButton);fpsButton.Location=new Point(20,195);fpsButton.Click+=async(_,_)=>{PlayClick();await OptimizeFPS();};
 var latencyButton=new Button(){Text="OPTIMIZE MY LATENCY",Width=260,Height=48,BackColor=Color.FromArgb(104,72,190),ForeColor=Color.White,FlatStyle=FlatStyle.Flat,Font=new Font("Segoe UI Semibold",10),Margin=new Padding(16,12,4,4)};latencyButton.FlatAppearance.BorderSize=0;lagCard.Controls.Add(latencyButton);latencyButton.Location=new Point(20,195);latencyButton.Click+=async(_,_)=>{PlayClick();await OptimizeLatency();};
 optimizeResult=new Label(){Text="Fortnite can be capped at 144 FPS by its settings. A GTX 1060 cannot hold 1,000 FPS in current Fortnite; this tool will check caps and stutter clues.",AutoSize=true,MaximumSize=new Size(900,0),ForeColor=Color.FromArgb(164,184,213),Font=new Font("Segoe UI",9),Margin=new Padding(4,8,0,15)};homeLayout.Controls.Add(optimizeResult);
 var note=new Label(){Text="Changes are reversible. Network tests can find local or route issues; they cannot change your ISP's route.",AutoSize=true,ForeColor=Color.FromArgb(121,140,171),Margin=new Padding(4,4,0,10)};homeLayout.Controls.Add(note);
 var monitorLayout=new Panel(){Dock=DockStyle.Fill,BackColor=BackColor,Padding=new Padding(18)};monitor.Controls.Add(monitorLayout);
 var mTitle=new Label(){Text="LIVE PC MONITOR",Dock=DockStyle.Top,Height=48,ForeColor=Color.White,Font=new Font("Segoe UI Semibold",18),Padding=new Padding(4,4,0,0)};monitorLayout.Controls.Add(mTitle);
 grid.Dock=DockStyle.Fill;foreach(var name in new[]{"PID","APP","CPU %","RAM MB","PUBLISHER","WHAT IT MAY BE"})grid.Columns.Add(name,name);grid.BackgroundColor=Color.FromArgb(13,21,36);grid.GridColor=Color.FromArgb(34,52,75);grid.DefaultCellStyle.BackColor=Color.FromArgb(13,21,36);grid.DefaultCellStyle.ForeColor=Color.FromArgb(222,231,244);grid.DefaultCellStyle.SelectionBackColor=Color.FromArgb(27,76,125);grid.ColumnHeadersDefaultCellStyle.BackColor=Color.FromArgb(18,38,62);grid.ColumnHeadersDefaultCellStyle.ForeColor=Color.FromArgb(113,194,255);grid.EnableHeadersVisualStyles=false;grid.RowHeadersVisible=false;grid.Height=500;monitorLayout.Controls.Add(grid);
 var actions=new FlowLayoutPanel(){Dock=DockStyle.Bottom,Height=55,WrapContents=false};monitorLayout.Controls.Add(actions);Add(actions,"Explain selected",()=>Explain());Add(actions,"Ask app to close",()=>CloseApp());Add(actions,"Task Manager",()=>Open("taskmgr.exe"));
 var settingsLayout=new FlowLayoutPanel(){Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,WrapContents=false,AutoScroll=true,Padding=new Padding(22),BackColor=BackColor};settings.Controls.Add(settingsLayout);settingsLayout.Controls.Add(new Label(){Text="SETTINGS & TOOLS",AutoSize=true,ForeColor=Color.White,Font=new Font("Segoe UI Semibold",22),Margin=new Padding(4,2,0,14)});InstallFeatures(settingsLayout);
 tray.Icon=BlockIcon();tray.Text="BlockCare";tray.Visible=true;var menu=new ContextMenuStrip();menu.Items.Add("Open BlockCare",null,(_,_)=>ShowDashboard());menu.Items.Add("Pause / resume monitoring",null,(_,_)=>timer.Enabled=!timer.Enabled);menu.Items.Add("Exit",null,(_,_)=>{quitting=true;Close();});tray.ContextMenuStrip=menu;tray.DoubleClick+=(_,_)=>ShowDashboard();
 FormClosing+=(_,e)=>{if(!quitting){e.Cancel=true;Hide();}else{timer.Stop();tray.Dispose();}};Shown+=(_,_)=>Hide();timer.Tick+=async(_,_)=>await RefreshApps();timer.Start();FindSound(false);
 }
 Panel MakeCard(string title,string description,Color accent,string icon){var p=new Panel(){Dock=DockStyle.Fill,BackColor=Color.FromArgb(15,25,43),Margin=new Padding(8),BorderStyle=BorderStyle.FixedSingle};p.Controls.Add(new Panel(){Dock=DockStyle.Top,Height=5,BackColor=accent});p.Controls.Add(new Label(){Text=icon,Location=new Point(20,22),Size=new Size(44,44),ForeColor=accent,Font=new Font("Segoe UI Symbol",27)});p.Controls.Add(new Label(){Text=title,Location=new Point(76,28),AutoSize=true,ForeColor=Color.White,Font=new Font("Segoe UI Semibold",13)});p.Controls.Add(new Label(){Text=description,Location=new Point(20,91),MaximumSize=new Size(385,65),AutoSize=true,ForeColor=Color.FromArgb(169,187,213),Font=new Font("Segoe UI",9)});return p;}
 void Add(Control parent,string text,Action action){var b=new Button(){Text=text,AutoSize=true,Height=40,BackColor=Color.FromArgb(75,103,50),ForeColor=Color.White,FlatStyle=FlatStyle.Flat,Margin=new Padding(6)}; b.Click+=(_,_)=>{try{PlayClick();action();}catch(Exception ex){MessageBox.Show(ex.Message,"BlockCare");}};parent.Controls.Add(b);}
 void ShowDashboard(){Show();WindowState=FormWindowState.Normal;Activate();}
 static void Open(string file)=>Process.Start(new ProcessStartInfo(file){UseShellExecute=true});
 async Task RefreshApps(){
 if(busy)return;busy=true;
 try{
 var rows=await Task.Run(()=>{
 var result=new List<object[]>();var now=DateTime.UtcNow;var next=new Dictionary<int,(double cpu,DateTime at)>();
 foreach(var p in Process.GetProcesses())using(p){try{
 var cpu=p.TotalProcessorTime.TotalSeconds;double usage=0;if(previous.TryGetValue(p.Id,out var old))usage=Math.Max(0,(cpu-old.cpu)/(now-old.at).TotalSeconds/Environment.ProcessorCount*100);next[p.Id]=(cpu,now);
 string company="Unavailable";try{company=p.MainModule?.FileVersionInfo.CompanyName??"Unknown";}catch{}
 result.Add(new object[]{p.Id,p.ProcessName,Math.Round(usage,1),Math.Round(p.WorkingSet64/1048576d),company,Describe(p.ProcessName)});
 }catch{}} previous=next;return result.OrderByDescending(x=>(double)x[2]).ToList();});
 int? selected=grid.SelectedRows.Count>0?Convert.ToInt32(grid.SelectedRows[0].Cells[0].Value):null;
 grid.Rows.Clear();foreach(var row in rows){var i=grid.Rows.Add(row);if(selected!=null&&(int)row[0]==selected)grid.Rows[i].Selected=true;}
 status.Text="BLOCKCARE  /  LIVE WORKSHOP\n"+rows.Count+" processes • updated "+DateTime.Now.ToLongTimeString()+" • 3-second sampling";
 }catch(Exception ex){status.Text="Monitor: "+ex.Message;}finally{busy=false;}
 }
 static string Describe(string n)=>n.ToLowerInvariant() switch {
 "chrome"=>"Browser; tabs and extensions can use separate processes.",
 "msedge"=>"Browser or embedded web content.",
 "discord"=>"Voice/chat; closing disconnects your calls.",
 "javaw" or "java"=>"Java app; often Minecraft, but confirm the window.",
 "fortniteclient-win64-shipping"=>"Fortnite game process.",
 "rocketleague"=>"Rocket League game process.",
 "svchost" or "lsass" or "csrss" or "wininit" or "services" or "system"=>"Windows component name. Leave running; name alone does not verify identity.",
 "msmpeng"=>"Microsoft Defender scanning process; leave protection enabled.",
 "nvcontainer"=>"NVIDIA component name; may support graphics features.",
 _=>"Inspect publisher and file location before deciding."
 };
 Process? Selected(){if(grid.SelectedRows.Count==0)return null;return Process.GetProcessById(Convert.ToInt32(grid.SelectedRows[0].Cells[0].Value));}
 void Explain(){using var p=Selected();if(p==null)return;string path="Access restricted";try{path=p.MainModule?.FileName??path;}catch{}MessageBox.Show(Describe(p.ProcessName)+"\n\nFile: "+path+"\n\nA publisher/name is not proof of safety. Use Windows Security to scan suspicious files.","About "+p.ProcessName);}
 void CloseApp(){using var p=Selected();if(p==null)return;if(p.Id==Environment.ProcessId||p.MainWindowHandle==IntPtr.Zero){MessageBox.Show("Only apps with a visible window can be closed here. Background services are protected from this action.");return;}if(MessageBox.Show("Ask "+p.ProcessName+" to close? Save your work first.","Close app",MessageBoxButtons.YesNo)==DialogResult.Yes){if(!p.CloseMainWindow())MessageBox.Show("The app did not accept the close request.");}}
 async Task Network(){
 var nic=NetworkInterface.GetAllNetworkInterfaces().FirstOrDefault(n=>n.OperationalStatus==OperationalStatus.Up&&n.NetworkInterfaceType==NetworkInterfaceType.Ethernet&&n.GetIPProperties().GatewayAddresses.Any());
 if(nic==null){MessageBox.Show("No active Ethernet gateway found.");return;}
 var gateway=nic.GetIPProperties().GatewayAddresses.First().Address.ToString();status.Text="Checking Ethernet latency — about 20 seconds";
 var report=new StringBuilder();
 foreach(var host in new[]{gateway,"1.1.1.1"}){var times=new List<long>();using var ping=new Ping();for(int i=0;i<10;i++){try{var r=await ping.SendPingAsync(host,800);if(r.Status==IPStatus.Success)times.Add(r.RoundtripTime);}catch{}await Task.Delay(200);}report.AppendLine(host+": "+(10-times.Count)*10+"% unanswered ICMP probes; "+(times.Count>0?times.Average().ToString("0.0")+" ms average":"no replies"));}
 MessageBox.Show(report+"\nICMP may be filtered or deprioritized. This is not proof of game packet loss and does not measure your game server route.","Ethernet check");
 }
 void Export(){using var dialog=new SaveFileDialog(){Filter="CSV report|*.csv",FileName="BlockCare-usage.csv"};if(dialog.ShowDialog()!=DialogResult.OK)return;string Q(object? s)=>"\""+Convert.ToString(s)?.Replace("\"","\"\"")+"\"";var lines=new List<string>{string.Join(",",grid.Columns.Cast<DataGridViewColumn>().Select(c=>Q(c.HeaderText)))};lines.AddRange(grid.Rows.Cast<DataGridViewRow>().Select(r=>string.Join(",",r.Cells.Cast<DataGridViewCell>().Select(c=>Q(c.Value)))));File.WriteAllLines(dialog.FileName,lines);}
}
