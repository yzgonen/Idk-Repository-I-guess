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
 Label status=new(){Dock=DockStyle.Top,Height=65,Padding=new Padding(16),ForeColor=Color.LightGreen};
 Dictionary<int,(double cpu,DateTime at)> previous=new(); bool quitting,busy;
 public Dashboard(){
 Text="BlockCare • PC workshop"; Size=new Size(1100,720); BackColor=Color.FromArgb(28,32,27); ForeColor=Color.White; Font=new Font("Consolas",11);
 var tabs=new TabControl(){Dock=DockStyle.Fill}; Controls.Add(tabs); Controls.Add(status);
 var apps=new TabPage("RUNNING APPS"); tabs.TabPages.Add(apps); apps.Controls.Add(grid);
 foreach(var name in new[]{"PID","App","CPU %","RAM MB","Publisher (reported)","Explanation"}) grid.Columns.Add(name,name);
 grid.BackgroundColor=BackColor; grid.DefaultCellStyle.BackColor=Color.FromArgb(42,48,39); grid.DefaultCellStyle.ForeColor=Color.White; grid.DefaultCellStyle.SelectionBackColor=Color.FromArgb(70,105,49); grid.RowHeadersVisible=false;
 var actions=new FlowLayoutPanel(){Dock=DockStyle.Bottom,Height=55}; apps.Controls.Add(actions);
 Add(actions,"Explain selected",()=>Explain()); Add(actions,"Ask app to close",()=>CloseApp()); Add(actions,"Task Manager",()=>Open("taskmgr.exe"));
 var tools=new TabPage("WORKBENCH"); tabs.TabPages.Add(tools); var panel=new FlowLayoutPanel(){Dock=DockStyle.Fill,Padding=new Padding(20),FlowDirection=FlowDirection.TopDown,WrapContents=false}; tools.Controls.Add(panel);
 Add(panel,"Ethernet latency check",async()=>await Network());
 Add(panel,"Windows virus protection",()=>Open("windowsdefender:"));
 Add(panel,"Review temporary files",()=>Open("ms-settings:storagesense"));
 Add(panel,"Review startup apps",()=>Open("ms-settings:startupapps"));
 Add(panel,"Windows Game Mode",()=>Open("ms-settings:gaming-gamemode"));
 Add(panel,"Chrome performance help",()=>Open("https://support.google.com/chrome/answer/12929150"));
 Add(panel,"Hardware / USB errors",()=>Open("devmgmt.msc"));
 Add(panel,"Save usage report",()=>Export());
 var games=new TabPage("GAME NOTES"); tabs.TabPages.Add(games);
 games.Controls.Add(new TextBox(){Multiline=true,ReadOnly=true,Dock=DockStyle.Fill,BackColor=BackColor,ForeColor=Color.White,Font=Font,Text="YOUR PC: i5-8400 / GTX 1060 6 GB / 16 GB RAM\r\n\r\nFortnite: compare Performance rendering mode with your current settings.\r\nRocket League: compare lower effects and a sustainable FPS cap.\r\nMinecraft: lower render and simulation distance when CPU usage spikes.\r\nChrome: Memory Saver can free memory from inactive tabs.\r\n\r\nUse the Running Apps page during a slowdown. CPU percentages use the whole processor. RAM totals per process can include shared memory.\r\n\r\nThis version measures process CPU and memory. It does not measure FPS, GPU usage, temperatures or per-app network traffic. Lower resource numbers alone do not prove smoother gameplay.\r\n\r\nPublisher labels are file metadata, NOT verified signatures or malware verdicts. Unknown apps need investigation. Windows Security performs virus checks.\r\n\r\nClosing this window keeps monitoring in the tray. Double-click the tray icon to open; right-click Exit to stop."});
 InstallFeatures(tabs, panel); tray.Icon=BlockIcon(); tray.Text="BlockCare"; tray.Visible=true; var menu=new ContextMenuStrip(); menu.Items.Add("Open",null,(_,_)=>ShowDashboard()); menu.Items.Add("Pause / resume",null,(_,_)=>timer.Enabled=!timer.Enabled); menu.Items.Add("Exit",null,(_,_)=>{quitting=true;Close();}); tray.ContextMenuStrip=menu; tray.DoubleClick+=(_,_)=>ShowDashboard();
 FormClosing+=(_,e)=>{if(!quitting){e.Cancel=true;Hide();}else{timer.Stop();tray.Dispose();}};
 Shown+=(_,_)=>Hide(); timer.Tick+=async(_,_)=>await RefreshApps(); timer.Start();
 }
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
