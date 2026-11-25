#define MyAppName "LuminaChord"
#define MyAppVersion "1.10"
#define MyAppPublisher "LuminaChord Contributors"
#define MyAppURL "https://github.com/Maizu28/LuminaChord"
#define MyAppExeName "LuminaChord.exe"
#define MyAppId "{{E8B2C4F1-5F3C-4E8A-9B12-7C8D9E0F1A23}}"

[Setup]
; NOTE: The value of AppId uniquely identifies this application.
AppId={#MyAppId}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppURL}
AppSupportURL={#MyAppURL}
AppUpdatesURL={#MyAppURL}
DefaultDirName={localappdata}\Programs\{#MyAppName}
DisableProgramGroupPage=yes
; Per-user install: No administrator privileges required
PrivilegesRequired=lowest
OutputDir=..\..\..\build\installer
OutputBaseFilename=LuminaChord-Windows-x64-Setup
SetupIconFile=..\icons\AppIcon.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[InstallDelete]
Type: files; Name: "{app}\app\desktopApp-*.jar"
Type: files; Name: "{app}\app\desktopUi-jvm-*.jar"
Type: files; Name: "{app}\app\shared-jvm-*.jar"

[Files]
Source: "..\..\build\compose\binaries\main\app\LuminaChord\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
; Start Menu shortcut: directly in Start Menu Programs so Windows Search indexes it instantly
Name: "{userprograms}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\{#MyAppExeName}"; AppUserModelID: "LuminaChord.Desktop"
; Desktop shortcut
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; IconFilename: "{app}\{#MyAppExeName}"

[Registry]
; Register App Paths for Windows Search and Win+R run dialog
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\App Paths\{#MyAppExeName}"; ValueType: string; ValueName: ""; ValueData: "{app}\{#MyAppExeName}"; Flags: uninsdeletekey
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\App Paths\{#MyAppExeName}"; ValueType: string; ValueName: "Path"; ValueData: "{app}"; Flags: uninsdeletekey

[Run]
Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Filename: "{app}\{#MyAppExeName}"; Flags: nowait postinstall skipifsilent
