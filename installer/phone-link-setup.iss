; Phone-Link Windows Installer — Inno Setup 7
; Build: ISCC.exe /DAppDir=<release-publish-dir> phone-link-setup.iss
; The AppId below is the permanent upgrade identity: never change it across
; future versions (v1.0.1, v1.1.0, ...) or upgrades will not be recognized.

#define MyAppName "Phone-Link"
#define MyAppVersion "1.0.0"
#define MyAppPublisher "Phone-Link"
#define MyAppExeName "PhoneLink.Desktop.exe"
#ifndef AppDir
  #define AppDir "staging"
#endif

[Setup]
AppId={{E77DCAFD-3D07-4F78-A1CD-436BF5CEE32C}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={localappdata}\Programs\Phone-Link
DefaultGroupName=Phone-Link
DisableProgramGroupPage=yes
OutputBaseFilename=Phone-Link-Setup-v1.0.0
OutputDir=..\artifacts\release\v1.0.0
SetupIconFile={#AppDir}\Assets\app.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
UninstallDisplayName=Phone-Link
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
RestartIfNeededByRun=no
CloseApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
Name: "chinesesimplified"; MessagesFile: "compiler:Languages\ChineseSimplified.isl"

[Tasks]
; Opt-in by default in the interactive wizard (unchecked). Note: Inno Setup
; silent installs (/SILENT /VERYSILENT) select all tasks unless /TASKS is given.
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
; Full release publish content (user data stays in %LOCALAPPDATA%\PhoneLink, never in {app}).
Source: "{#AppDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Phone-Link"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\Phone-Link"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchProgram,{#StringChange(MyAppName, '&', '&&')}}"; Flags: nowait postinstall skipifsilent

[Code]
function IsAppRunning(): Boolean;
var
  Tmp: string;
  ResultCode: Integer;
  Content: TStringList;
begin
  Result := False;
  Tmp := GetTempDir + 'phonelink-tasklist.txt';
  DeleteFile(Tmp);
  Exec('tasklist.exe', '/FI "IMAGENAME eq PhoneLink.Desktop.exe" /FO CSV /NH', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  if FileExists(Tmp) then
  begin
    Content := TStringList.Create;
    try
      Content.LoadFromFile(Tmp);
      Result := Pos('phonelink.desktop.exe', LowerCase(Content.Text)) > 0;
    finally
      Content.Free;
    end;
  end;
end;

function InitializeSetup(): Boolean;
begin
  Result := True;
  if IsAppRunning() then
  begin
    MsgBox('Phone-Link is currently running. Please exit it first via the tray icon (right-click the tray icon -> Exit), then retry the setup.', mbError, MB_OK);
    Result := False;
  end;
end;