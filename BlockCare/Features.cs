using System.Diagnostics;
using System.Text.Json;
using Microsoft.Win32;
using System.Runtime.InteropServices;
namespace BlockCare;
partial class Dashboard {
 string data=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"BlockCare"); // Preserve existing tune-up backups.
 bool sound=true; string? clickFile; readonly NAudio.Wave.WaveOutEvent speaker=new(); NAudio.Wave.WaveStream? audio;
 CheckBox? focusEnabled, focusChrome, focusDiscord, focusWhatsApp;
 readonly Dictionary<int,ProcessPriorityClass> savedPriorities=new();
 Label gpu=new(){AutoSize=true,MaximumSize=new Size(950,0)};
 static Icon ZennIcon(){using var b=new Bitmap(32,32);using(var g=Graphics.FromImage(b)){g.Clear(Color.Transparent);using var brush=new SolidBrush(Color.FromArgb(68,151,255));g.FillPolygon(brush,new[]{new Point(20,1),new Point(7,18),new Point(15,18),new Point(11,31),new Point(26,12),new Point(18,12)});}var h=b.GetHicon();try{return (Icon)Icon.FromHandle(h).Clone();}finally{DestroyIcon(h);}}
 [DllImport("user32.dll")] static extern bool DestroyIcon(IntPtr h);
 void InstallFeatures(FlowLayoutPanel panel){
 var intro=new Label(){Text="A few focused controls for your apps. Windows changes remain reversible.",AutoSize=true,ForeColor=Color.FromArgb(171,175,172),Margin=new Padding(4,0,0,12)};panel.Controls.Add(intro);
 var focusBox=new Panel(){Width=900,Height=114,BackColor=Color.FromArgb(24,26,25),Margin=new Padding(4,4,4,14),Padding=new Padding(14)};
 focusEnabled=new CheckBox(){Text="Game Focus: lower selected apps' CPU priority while a game is open",AutoSize=true,ForeColor=Color.White,Font=new Font("Segoe UI Semibold",10),Location=new Point(12,10)};
 focusChrome=new CheckBox(){Text="Chrome",Checked=true,AutoSize=true,ForeColor=Color.FromArgb(210,213,211),Location=new Point(14,50)};
 focusDiscord=new CheckBox(){Text="Discord",Checked=true,AutoSize=true,ForeColor=Color.FromArgb(210,213,211),Location=new Point(130,50)};
 focusWhatsApp=new CheckBox(){Text="WhatsApp",Checked=true,AutoSize=true,ForeColor=Color.FromArgb(210,213,211),Location=new Point(250,50)};
 var focusNote=new Label(){Text="Only active during Fortnite, Rocket League, or Minecraft; normal priority is restored when the game closes. Priority affects CPU scheduling, not RAM.",AutoSize=true,MaximumSize=new Size(840,0),ForeColor=Color.FromArgb(165,171,169),Location=new Point(14,78),Font=new Font("Segoe UI",8)};
 focusBox.Controls.AddRange(new Control[]{focusEnabled,focusChrome,focusDiscord,focusWhatsApp,focusNote});panel.Controls.Add(focusBox);
 panel.Controls.Add(gpu);
 Add(panel,"Undo previous Zenn tune-up",()=>UndoPC());
 Add(panel,"Check GPU and storage",async()=>await Hardware());
 Add(panel,"Chrome performance settings",()=>OpenChromePerformance());
 Add(panel,"Review startup apps",()=>Open("ms-settings:startupapps"));
 Add(panel,"Review temporary files",()=>Open("ms-settings:storagesense"));
 Add(panel,"Windows Security / virus scan",()=>Open("windowsdefender:"));
 Add(panel,"Show recent Windows errors",async()=>await Errors());
 panel.AutoScroll=true;FormClosed+=(_,_)=>{RestorePriorities();speaker.Dispose();audio?.Dispose();};
 }
 void UpdateGameFocus(){
 bool active=Process.GetProcesses().Any(p=>{using(p){try{return new[]{"FortniteClient-Win64-Shipping","RocketLeague","javaw","java"}.Contains(p.ProcessName,StringComparer.OrdinalIgnoreCase);}catch{return false;}}});
 bool enabled=focusEnabled?.Checked==true&&active;
 foreach(var pair in savedPriorities.ToArray()){
  bool keep=false;
  if(enabled){try{using var old=Process.GetProcessById(pair.Key);keep=IsSelectedTarget(old.ProcessName);}catch{}}
  if(!keep){try{using var old=Process.GetProcessById(pair.Key);old.PriorityClass=pair.Value;}catch{}savedPriorities.Remove(pair.Key);}
 }
 if(!enabled)return;
 foreach(var p in Process.GetProcesses())using(p){try{
  if(!IsSelectedTarget(p.ProcessName)||savedPriorities.ContainsKey(p.Id))continue;
  var current=p.PriorityClass;savedPriorities[p.Id]=current;
  if(current==ProcessPriorityClass.Normal||current==ProcessPriorityClass.AboveNormal||current==ProcessPriorityClass.High||current==ProcessPriorityClass.RealTime)p.PriorityClass=ProcessPriorityClass.BelowNormal;
 }catch{}}
 }
 bool IsSelectedTarget(string name){
  if(name.Equals("chrome",StringComparison.OrdinalIgnoreCase))return focusChrome?.Checked==true;
  if(name.Equals("discord",StringComparison.OrdinalIgnoreCase))return focusDiscord?.Checked==true;
  if(name.Equals("WhatsApp",StringComparison.OrdinalIgnoreCase)||name.Equals("WhatsApp.Root",StringComparison.OrdinalIgnoreCase))return focusWhatsApp?.Checked==true;
  return false;
 }
 void RestorePriorities(){
  foreach(var pair in savedPriorities.ToArray()){try{using var p=Process.GetProcessById(pair.Key);p.PriorityClass=pair.Value;}catch{}}
  savedPriorities.Clear();
 }
 void PlayClick(){if(!sound||clickFile==null)return;try{speaker.Stop();audio?.Dispose();audio=new NAudio.Vorbis.VorbisWaveReader(clickFile);speaker.Init(audio);speaker.Volume=.3f;speaker.Play();}catch{}}
 void FindSound(bool notify=true){
 var root=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),".minecraft","assets");
 try{foreach(var file in Directory.GetFiles(Path.Combine(root,"indexes"),"*.json").OrderByDescending(File.GetLastWriteTimeUtc)){
 using var doc=JsonDocument.Parse(File.ReadAllText(file));var objects=doc.RootElement.GetProperty("objects");
 foreach(var entry in objects.EnumerateObject()){if(!entry.Name.EndsWith("sounds/random/click.ogg"))continue;var hash=entry.Value.GetProperty("hash").GetString()!;var path=Path.Combine(root,"objects",hash[..2],hash);if(File.Exists(path)){clickFile=path;if(notify)MessageBox.Show("Minecraft button sound loaded from your installation.");return;}}}
 }catch{}if(notify)MessageBox.Show("No Java click sound found in the default .minecraft assets. Launch Minecraft Java once, then retry.");
 }
 async Task<string> Run(string exe,string args){using var p=new Process(){StartInfo=new ProcessStartInfo(exe,args){RedirectStandardOutput=true,RedirectStandardError=true,UseShellExecute=false,CreateNoWindow=true}};p.Start();var output=p.StandardOutput.ReadToEndAsync();var err=p.StandardError.ReadToEndAsync();using var timeout=new CancellationTokenSource(TimeSpan.FromSeconds(40));try{await p.WaitForExitAsync(timeout.Token);}catch(OperationCanceledException){p.Kill(true);return "Command timed out.";}var o=await output;var e=await err;return p.ExitCode==0?o:"Unavailable: "+e;}
 async Task<string> PS(string code)=>await Run("powershell.exe","-NoProfile -NonInteractive -EncodedCommand "+Convert.ToBase64String(System.Text.Encoding.Unicode.GetBytes(code)));
 async Task Hardware(){gpu.Text="Reading hardware…";var n=await Run("nvidia-smi.exe","--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu --format=csv");var disks=await PS("Get-PhysicalDisk | Select FriendlyName,MediaType,HealthStatus,Size | Format-Table -AutoSize | Out-String");gpu.Text=n+"\r\n"+disks+"\r\nGPU readings are a snapshot. Recheck while a game runs. FPS is not inferred from utilization.";MessageBox.Show(gpu.Text,"Zenn hardware snapshot");}
 async Task Errors(){var s=await PS("Get-WinEvent -FilterHashtable @{LogName='System';Level=2;StartTime=(Get-Date).AddDays(-2)} -MaxEvents 20 -ErrorAction SilentlyContinue | Select TimeCreated,ProviderName,Id,Message | Format-List | Out-String");using var f=new Form(){Text="Recent system errors — clues, not a diagnosis",Size=new Size(900,650)};f.Controls.Add(new TextBox(){Multiline=true,ReadOnly=true,ScrollBars=ScrollBars.Both,Dock=DockStyle.Fill,Text=s});f.ShowDialog();}
 void Startup(bool enabled){using var key=Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run",true);if(key==null)throw new Exception("Startup registry unavailable.");if(enabled)key.SetValue("Zenn","\""+Environment.ProcessPath+"\"");else key.DeleteValue("Zenn",false);MessageBox.Show(enabled?"Zenn will start in the tray when you sign in. Keep the executable at this location.":"Automatic startup disabled.");}
 async void PowerOn(){try{var file=Path.Combine(data,"power-plan.txt");if(File.Exists(file)){MessageBox.Show("A previous plan is already saved. Restore it before applying again.");return;}var current=await Run("powercfg.exe","/getactivescheme");var match=System.Text.RegularExpressions.Regex.Match(current,@"[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");if(!match.Success){MessageBox.Show(current);return;}if(MessageBox.Show("Switch to High Performance? This can increase power use and heat. It does not guarantee more FPS.","Gaming power",MessageBoxButtons.YesNo)!=DialogResult.Yes)return;File.WriteAllText(file,match.Value);var result=await Run("powercfg.exe","/setactive SCHEME_MIN");MessageBox.Show(string.IsNullOrWhiteSpace(result)?"Applied. Use Restore previous power plan to undo.":result);}catch(Exception ex){MessageBox.Show(ex.Message);}}
 async void PowerOff(){try{var file=Path.Combine(data,"power-plan.txt");if(!File.Exists(file)){MessageBox.Show("No saved plan to restore.");return;}var id=File.ReadAllText(file).Trim();if(!Guid.TryParse(id,out _))throw new Exception("Saved plan is invalid.");var r=await Run("powercfg.exe","/setactive "+id);if(string.IsNullOrWhiteSpace(r)){File.Delete(file);MessageBox.Show("Previous power plan restored.");}else MessageBox.Show(r);}catch(Exception ex){MessageBox.Show(ex.Message);}}
 void Scan(){var exe=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),"Windows Defender","MpCmdRun.exe");if(!File.Exists(exe)){Open("windowsdefender:");return;}Process.Start(new ProcessStartInfo(exe,"-Scan -ScanType 1"){UseShellExecute=true,Verb="runas"});}
 sealed class SavedPlan { public string Power {get;set;}=""; public int? GameMode {get;set;} public int? GameModeAllowed {get;set;} public int? ChromeSaver {get;set;} }
 int? ReadDword(string path,string name){using var k=Registry.CurrentUser.OpenSubKey(path);var v=k?.GetValue(name);return v==null?null:Convert.ToInt32(v);}
 void WriteDword(string path,string name,int? value){using var k=Registry.CurrentUser.CreateSubKey(path,true);if(value.HasValue)k.SetValue(name,value.Value,RegistryValueKind.DWord);else k.DeleteValue(name,false);}
 async void OptimizePC(){
 try{
 var file=Path.Combine(data,"optimization-backup.json");if(File.Exists(file)){MessageBox.Show("Zenn already has a saved optimization. Use Undo before applying again.");return;}
 var active=await Run("powercfg.exe","/getactivescheme");var match=System.Text.RegularExpressions.Regex.Match(active,@"[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");if(!match.Success){MessageBox.Show("Could not read your current power plan. No settings were changed.");return;}
 const string game=@"Software\Microsoft\GameBar";
 var backup=new SavedPlan{Power=match.Value,GameMode=ReadDword(game,"AutoGameModeEnabled"),GameModeAllowed=ReadDword(game,"AllowAutoGameMode")};
 Directory.CreateDirectory(data);File.WriteAllText(file,JsonSerializer.Serialize(backup));
 WriteDword(game,"AutoGameModeEnabled",1);WriteDword(game,"AllowAutoGameMode",1);
 var result=await Run("powercfg.exe","/setactive SCHEME_MIN");
 if(!string.IsNullOrWhiteSpace(result)){await UndoPCAsync();MessageBox.Show("Windows could not apply the power plan. Previous settings were restored.\n"+result);return;}
 MessageBox.Show("Optimization applied:\n• Windows switched to High Performance.\n• Windows Game Mode was enabled.\n\nThis may increase heat and power use. Use Undo Zenn optimization to restore your previous settings.","Zenn optimization");
 }catch(Exception ex){var backup=Path.Combine(data,"optimization-backup.json");if(File.Exists(backup)){try{await UndoPCAsync();}catch{}}MessageBox.Show("Could not finish the optimization: "+ex.Message+"\nAny saved settings were restored where possible.");}
 }
 void OpenChromePerformance(){
 var paths=new[]{Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Google","Chrome","Application","chrome.exe"),Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),"Google","Chrome","Application","chrome.exe"),Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),"Google","Chrome","Application","chrome.exe")};
 var exe=paths.FirstOrDefault(File.Exists);if(exe!=null)Process.Start(new ProcessStartInfo(exe,"--new-tab chrome://settings/performance"){UseShellExecute=true});else MessageBox.Show("Chrome was not found. Open Chrome and enter chrome://settings/performance to turn on Memory Saver.");
 }
 async Task UndoPCAsync(){
 var file=Path.Combine(data,"optimization-backup.json");if(!File.Exists(file))throw new Exception("No saved optimization to undo.");
 var b=JsonSerializer.Deserialize<SavedPlan>(File.ReadAllText(file))??throw new Exception("Saved settings backup is unreadable.");
 if(!Guid.TryParse(b.Power,out _))throw new Exception("Saved power plan is invalid.");
 var problems=new List<string>();
 try{WriteDword(@"Software\Microsoft\GameBar","AutoGameModeEnabled",b.GameMode);WriteDword(@"Software\Microsoft\GameBar","AllowAutoGameMode",b.GameModeAllowed);}catch(Exception ex){problems.Add("Game Mode: "+ex.Message);}
 if(b.ChromeSaver.HasValue){try{WriteDword(@"Software\Policies\Google\Chrome","HighEfficiencyModeEnabled",b.ChromeSaver);}catch(Exception ex){problems.Add("Chrome policy: "+ex.Message);}}
 var result=await Run("powercfg.exe","/setactive "+b.Power);if(!string.IsNullOrWhiteSpace(result))problems.Add("Power plan: "+result);
 if(problems.Count==0)File.Delete(file);else throw new Exception("Some settings could not be restored:\n"+string.Join("\n",problems));
 }
 async void UndoPC(){try{await UndoPCAsync();MessageBox.Show("Your saved Windows settings were restored.");}catch(Exception ex){MessageBox.Show(ex.Message);}}
 async Task<string> ApplyGamingProfile(){
 var file=Path.Combine(data,"optimization-backup.json");
 if(File.Exists(file))return "Your reversible gaming profile is already active.";
 var active=await Run("powercfg.exe","/getactivescheme");var match=System.Text.RegularExpressions.Regex.Match(active,@"[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
 if(!match.Success)return "Couldn't read your current power plan, so no changes were made.";
 const string game=@"Software\Microsoft\GameBar";
 var backup=new SavedPlan{Power=match.Value,GameMode=ReadDword(game,"AutoGameModeEnabled"),GameModeAllowed=ReadDword(game,"AllowAutoGameMode")};
 Directory.CreateDirectory(data);File.WriteAllText(file,JsonSerializer.Serialize(backup));
 try{WriteDword(game,"AutoGameModeEnabled",1);WriteDword(game,"AllowAutoGameMode",1);var result=await Run("powercfg.exe","/setactive SCHEME_MIN");if(!string.IsNullOrWhiteSpace(result))throw new Exception(result);return "Windows Game Mode and High Performance power plan enabled. This may increase heat and power use. Use Settings → Undo to restore.";}
 catch(Exception ex){try{await UndoPCAsync();}catch{}return "Windows couldn't apply the gaming profile: "+ex.Message;}
 }
 async Task OptimizeFPS(){
 optimizeResult.Text="Applying safe Windows gaming settings and checking "+gameChoice.SelectedItem+"…";
 var profile=await ApplyGamingProfile();string detail;
 if(Convert.ToString(gameChoice.SelectedItem)=="Fortnite")detail=await InspectFortnite();
 else if(Convert.ToString(gameChoice.SelectedItem)=="Rocket League")detail="Rocket League settings were left intact. Check its FPS cap and effects in game; see Live Monitor while it runs.";
 else detail="Minecraft settings were left intact. If CPU spikes, lower render and simulation distance; see Live Monitor while it runs.";
 optimizeResult.Text=profile+"\n\n"+detail+"\n\nFPS depends on game settings, temperatures, drivers, and the scene. The check above reports the settings Zenn can safely read.";
 }
 async Task<string> InspectFortnite(){
 var file=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"FortniteGame","Saved","Config","WindowsClient","GameUserSettings.ini");
 if(!File.Exists(file))return "Fortnite settings file not found. Launch Fortnite once, then run this check again.";
 try{var text=await File.ReadAllTextAsync(file);var cap=System.Text.RegularExpressions.Regex.Match(text,@"(?im)^\s*FrameRateLimit\s*=\s*([0-9.]+)");var vs=System.Text.RegularExpressions.Regex.Match(text,@"(?im)^\s*bUseVSync\s*=\s*(True|False)");
 string capText=cap.Success?cap.Groups[1].Value:"not detected";string vsText=vs.Success?vs.Groups[1].Value:"not detected";
 return "Fortnite config check: frame limit "+capText+" FPS; V-Sync "+vsText+". If it reads 144, the config is capping at 144; check Fortnite's in-game Video settings too. This reads settings and doesn't alter them.";
 }catch(Exception ex){return "Couldn't read Fortnite settings: "+ex.Message;}
 }
 async Task OptimizeLatency(){
 optimizeResult.Text="Applying Windows Game Mode and checking your wired connection…";var profile=await ApplyGamingProfile();optimizeResult.Text=profile+"\n\nPing check starting.";
 await Network();
 optimizeResult.Text=profile+"\n\nCheck the Ethernet results. This test can't change your ISP route or guarantee lower game ping.";
 }
 async Task Signature(){using var p=Selected();if(p==null){MessageBox.Show("Select an app on Running Apps first.");return;}string path;try{path=p.MainModule!.FileName;}catch{MessageBox.Show("Windows restricts access to this process.");return;}var escaped=path.Replace("'","''");var text=await PS("Get-AuthenticodeSignature -LiteralPath '"+escaped+"' | Select Status,StatusMessage,@{N='Signer';E={$_.SignerCertificate.Subject}} | Format-List | Out-String");MessageBox.Show(text+"\nA valid signature verifies the signer, not that an app is necessary or harmless.","Signature verification");}
}
