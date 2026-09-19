using Microsoft.Win32;
using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Windows.Forms;

internal static class Program {
 [STAThread] static void Main() {
  Application.EnableVisualStyles();
  string source=Path.Combine(AppContext.BaseDirectory,"CodeRed.dll");
  if(!File.Exists(source)){MessageBox.Show("CodeRed.dll is missing next to RLTAS-Setup.exe.","RLTAS Setup",MessageBoxButtons.OK,MessageBoxIcon.Error);return;}
  string install=Registry.CurrentUser.OpenSubKey(@"CodeRedModding")?.GetValue("InstallPath") as string ?? "";
  if(string.IsNullOrWhiteSpace(install)||!Directory.Exists(install)){
   using var dlg=new FolderBrowserDialog{Description="Select your CodeRed installation folder"};
   if(dlg.ShowDialog()!=DialogResult.OK)return; install=dlg.SelectedPath;
  }
  string dllDir=Path.Combine(install,"DLL"); Directory.CreateDirectory(dllDir);
  string dest=Path.Combine(dllDir,"CodeRed.dll");
  if(Process.GetProcessesByName("RocketLeague").Any()){MessageBox.Show("Close Rocket League before installing RLTAS.","RLTAS Setup",MessageBoxButtons.OK,MessageBoxIcon.Warning);return;}
  try {
   if(File.Exists(dest)){string backup=Path.Combine(dllDir,"CodeRed.backup-"+DateTime.Now.ToString("yyyyMMdd-HHmmss")+".dll");File.Copy(dest,backup,false);}
   string temp=dest+".rltas-new"; File.Copy(source,temp,true);
   if(!SHA256.HashData(File.ReadAllBytes(source)).SequenceEqual(SHA256.HashData(File.ReadAllBytes(temp))))throw new IOException("SHA-256 verification failed.");
   File.Move(temp,dest,true);
   MessageBox.Show("RLTAS installed successfully.\n\nUse CodeRed only in the offline / Freeplay setup intended for this build.","RLTAS Setup",MessageBoxButtons.OK,MessageBoxIcon.Information);
  } catch(Exception ex){MessageBox.Show("Installation failed:\n"+ex.Message,"RLTAS Setup",MessageBoxButtons.OK,MessageBoxIcon.Error);}
 }
}