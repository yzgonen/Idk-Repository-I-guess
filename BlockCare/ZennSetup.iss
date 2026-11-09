#define AppName "Zenn"
#define AppVersion "0.3.0"
#define AppPublisher "Zenn"
#define AppExeName "Zenn.exe"

[Setup]
AppId={{1A86A27D-7630-42EA-A5DD-7092DA86B0A2}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={localappdata}\Programs\Zenn
DefaultGroupName=Zenn
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesInstallIn64BitMode=x64
OutputDir=output
OutputBaseFilename=ZennSetup
WizardStyle=modern
Compression=lzma2/ultra64
SolidCompression=yes
CloseApplications=yes
RestartApplications=no
UninstallDisplayIcon={app}\Zenn.exe
SetupLogging=yes

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"

[Files]
Source: "output\Zenn.exe"; DestDir: "{app}"; Flags: ignoreversion

[Icons]
Name: "{group}\Zenn"; Filename: "{app}\Zenn.exe"
Name: "{autodesktop}\Zenn"; Filename: "{app}\Zenn.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Zenn.exe"; Description: "Launch Zenn"; Flags: postinstall nowait skipifsilent
