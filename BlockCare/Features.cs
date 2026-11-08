using System.Diagnostics;
using System.Text.Json;
using Microsoft.Win32;
using System.Runtime.InteropServices;
namespace BlockCare;
partial class Dashboard {
 string data=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"BlockCare");
 bool sound=true; string? clickFile; readonly NAudio.Wave.WaveOutEvent speaker=new(); NAudio.Wave.WaveStream? audio;
 Label gpu=new(){AutoSize=true,MaximumSize=new Size(950,0)};
 static Icon BlockIcon(){using var b=new Bitmap(32,32);using(var g=Graphics.FromImage(b)){g.Clear(Color.Transparent);g.FillRectangle(Brushes.SaddleBrown,4,12,24,16);g.FillRectangle(Brushes.ForestGreen,4,4,24,10);g.FillRectangle(Brushes.LightGreen,4,4,8,4);g.FillRectangle(Brushes.Peru,8,18,4,4);g.FillRectangle(Brushes.Peru,20,24,4,4);}var h=b.GetHicon();try{return (Icon)Icon.FromHandle(h).Clone();}finally{DestroyIcon(h);}}
 [DllImport("user32.dll")] static extern bool DestroyIcon(IntPtr h);
 void InstallFeatures(TabControl tabs,FlowLayoutPanel panel){
 Directory.CreateDirectory(data);
 var health=new TabPage("HARDWARE & HISTORY"); tabs.TabPages.Add(health);var box=new FlowLayoutPanel(){Dock=DockStyle.Fill,FlowDirection=FlowDirection.TopDown,Padding=new Padding(20),AutoScroll=true};health.Controls.Add(box);box.Controls.Add(gpu);
 Add(box,"Read GPU and storage",async()=>await Hardware());
 Add(box,"Recent Windows errors",async()=>await Errors());
 Add(panel,"Optimize my PC (recommended)",()=>OptimizePC());Add(panel,"Undo BlockCare optimization",()=>UndoPC());
 Add(panel,"Start Defender quick scan",()=>Scan());
 Add(panel,"Launch BlockCare with Windows",()=>Startup(true));Add(panel,"Stop launching with Windows",()=>Startup(false));
 Add(panel,"Load Minecraft Java sounds",()=>FindSound());Add(panel,"Mute / unmute sounds",()=>{sound=!sound;MessageBox.Show(sound?"Sounds on":"Sounds off");});
 Add(panel,"Verify selected app signature",async()=>await Signature());
 panel.AutoScroll=true;
 FindSound(false);
 FormClosed+=(_,_)=>{speaker.Dispose();audio?.Dispose();};
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
 async Task Hardware(){gpu.Text="Reading hardware…";var n=await Run("nvidia-smi.exe","--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu --format=csv");var disks=await PS("Get-PhysicalDisk | Select FriendlyName,MediaType,HealthStatus,Size | Format-Table -AutoSize | Out-String");gpu.Text=n+"\r\n"+disks+"\r\nGPU readings are a snapshot. Recheck while a game runs. FPS is not inferred from utilization.";}
 async Task Errors(){var s=await PS("Get-WinEvent -FilterHashtable @{LogName='System';Level=2;StartTime=(Get-Date).AddDays(-2)} -MaxEvents 20 -ErrorAction SilentlyContinue | Select TimeCreated,ProviderName,Id,Message | Format-List | Out-String");using var f=new Form(){Text="Recent system errors — clues, not a diagnosis",Size=new Size(900,650)};f.Controls.Add(new TextBox(){Multiline=true,ReadOnly=true,ScrollBars=ScrollBars.Both,Dock=DockStyle.Fill,Text=s});f.ShowDialog();}
 void Startup(bool enabled){using var key=Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run",true);if(key==null)throw new Exception("Startup registry unavailable.");if(enabled)key.SetValue("BlockCare","\""+Environment.ProcessPath+"\"");else key.DeleteValue("BlockCare",false);MessageBox.Show(enabled?"BlockCare will start in the tray when you sign in. Keep the executable at this location.":"Automatic startup disabled.");}
 async void PowerOn(){try{var file=Path.Combine(data,"power-plan.txt");if(File.Exists(file)){MessageBox.Show("A previous plan is already saved. Restore it before applying again.");return;}var current=await Run("powercfg.exe","/getactivescheme");var match=System.Text.RegularExpressions.Regex.Match(current,@"[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");if(!match.Success){MessageBox.Show(current);return;}if(MessageBox.Show("Switch to High Performance? This can increase power use and heat. It does not guarantee more FPS.","Gaming power",MessageBoxButtons.YesNo)!=DialogResult.Yes)return;File.WriteAllText(file,match.Value);var result=await Run("powercfg.exe","/setactive SCHEME_MIN");MessageBox.Show(string.IsNullOrWhiteSpace(result)?"Applied. Use Restore previous power plan to undo.":result);}catch(Exception ex){MessageBox.Show(ex.Message);}}
 async void PowerOff(){try{var file=Path.Combine(data,"power-plan.txt");if(!File.Exists(file)){MessageBox.Show("No saved plan to restore.");return;}var id=File.ReadAllText(file).Trim();if(!Guid.TryParse(id,out _))throw new Exception("Saved plan is invalid.");var r=await Run("powercfg.exe","/setactive "+id);if(string.IsNullOrWhiteSpace(r)){File.Delete(file);MessageBox.Show("Previous power plan restored.");}else MessageBox.Show(r);}catch(Exception ex){MessageBox.Show(ex.Message);}}
 void Scan(){var exe=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),"Windows Defender","MpCmdRun.exe");if(!File.Exists(exe)){Open("windowsdefender:");return;}Process.Start(new ProcessStartInfo(exe,"-Scan -ScanType 1"){UseShellExecute=true,Verb="runas"});}
 sealed class SavedPlan { public string Power {get;set;}=""; public int? GameMode {get;set;} public int? GameModeAllowed {get;set;} public int? ChromeSaver {get;set;} }
 int? ReadDword(string path,string name){using var k=Registry.CurrentUser.OpenSubKey(path);var v=k?.GetValue(name);return v==null?null:Convert.ToInt32(v);}
 void WriteDword(string path,string name,int? value){using var k=Registry.CurrentUser.CreateSubKey(path,true);if(value.HasValue)k.SetValue(name,value.Value,RegistryValueKind.DWord);else k.DeleteValue(name,false);}
 async void OptimizePC(){
 try{
 var file=Path.Combine(data,"optimization-backup.json");if(File.Exists(file)){MessageBox.Show("BlockCare already has a saved optimization. Use Undo before applying again.");return;}
 var active=await Run("powercfg.exe","/getactivescheme");var match=System.Text.RegularExpressions.Regex.Match(active,@"[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");if(!match.Success){MessageBox.Show("Could not read your current power plan. No settings were changed.");return;}
 const string game=@"Software\\Microsoft\\GameBar", chrome=@"Software\\Policies\\Google\\Chrome";
 var backup=new SavedPlan{Power=match.Value,GameMode=ReadDword(game,"AutoGameModeEnabled"),GameModeAllowed=ReadDword(game,"AllowAutoGameMode"),ChromeSaver=ReadDword(chrome,"HighEfficiencyModeEnabled")};
 Directory.CreateDirectory(data);File.WriteAllText(file,JsonSerializer.Serialize(backup));
 WriteDword(game,"AutoGameModeEnabled",1);WriteDword(game,"AllowAutoGameMode",1);WriteDword(chrome,"HighEfficiencyModeEnabled",1);
 var result=await Run("powercfg.exe","/setactive SCHEME_MIN");
 if(!string.IsNullOrWhiteSpace(result)){await UndoPCAsync();MessageBox.Show("Windows could not apply the power plan. Previous settings were restored.\\n"+result);return;}
 Open("https://support.google.com/chrome/answer/12929150");
 MessageBox.Show("Optimization applied:\\n• Windows switched to High Performance.\\n• Windows Game Mode was enabled.\\n• Chrome Memory Saver was enabled by its supported Windows setting.\\n\\nRestart Chrome for its setting to take effect. Open chrome://settings/performance to review it.\\n\\nThis may increase heat and power use. Use Undo BlockCare optimization to restore your previous settings.","BlockCare optimization");
 }catch(Exception ex){var backup=Path.Combine(data,"optimization-backup.json");if(File.Exists(backup)){try{await UndoPCAsync();}catch{}}MessageBox.Show("Could not finish the optimization: "+ex.Message+"\\nAny saved settings were restored where possible.");}
 }
 async Task UndoPCAsync(){
 var file=Path.Combine(data,"optimization-backup.json");if(!File.Exists(file))throw new Exception("No saved optimization to undo.");
 var b=JsonSerializer.Deserialize<SavedPlan>(File.ReadAllText(file))??throw new Exception("Saved settings backup is unreadable.");
 if(!Guid.TryParse(b.Power,out _))throw new Exception("Saved power plan is invalid.");
 WriteDword(@"Software\\Microsoft\\GameBar","AutoGameModeEnabled",b.GameMode);WriteDword(@"Software\\Microsoft\\GameBar","AllowAutoGameMode",b.GameModeAllowed);WriteDword(@"Software\\Policies\\Google\\Chrome","HighEfficiencyModeEnabled",b.ChromeSaver);
 var result=await Run("powercfg.exe","/setactive "+b.Power);if(!string.IsNullOrWhiteSpace(result))throw new Exception(result);File.Delete(file);
 }
 async void UndoPC(){try{await UndoPCAsync();MessageBox.Show("Your saved Windows and Chrome settings were restored. Restart Chrome if it is open.");}catch(Exception ex){MessageBox.Show(ex.Message);}}
 async Task Signature(){using var p=Selected();if(p==null){MessageBox.Show("Select an app on Running Apps first.");return;}string path;try{path=p.MainModule!.FileName;}catch{MessageBox.Show("Windows restricts access to this process.");return;}var escaped=path.Replace("'","''");var text=await PS("Get-AuthenticodeSignature -LiteralPath '"+escaped+"' | Select Status,StatusMessage,@{N='Signer';E={$_.SignerCertificate.Subject}} | Format-List | Out-String");MessageBox.Show(text+"\nA valid signature verifies the signer, not that an app is necessary or harmless.","Signature verification");}
}
