function WriteIniVisible(isVisible)
{
   fso   = new ActiveXObject('Scripting.FileSystemObject');

   rfile = fso.OpenTextFile('proxy.ini', 1);
   content = rfile.ReadAll();
   rfile.Close()

   content = content.replace(/visible\s*=[^\r]*/ig, 'visible = ' + isVisible);

   wfile = fso.OpenTextFile('proxy.ini', 2);
   wfile.Write(content);
   wfile.Close();
}

function CreateShortcut(target_path)
{
   wsh = new ActiveXObject('WScript.Shell');
   link = wsh.CreateShortcut(wsh.SpecialFolders("Startup") + '\\DNSProxy.lnk');
   link.TargetPath = target_path;
   link.WindowStyle = 7;
   link.Description = 'DNS Proxy';
   link.WorkingDirectory = wsh.CurrentDirectory;
   link.Save();
}

function main()
{
   wsh = new ActiveXObject('WScript.Shell');
   fso = new ActiveXObject('Scripting.FileSystemObject');

   if(wsh.Popup('是否将dnsProxy.exe加入到启动项？(本对话框6秒后消失)', 6, 'DNS Proxy 对话框', 1+32) == 1) {
       CreateShortcut('"' + wsh.CurrentDirectory + '\\dnsProxy.exe"');
       //WriteIniVisible('0');
       wsh.Popup('成功加入DNS Proxy到启动项', 5, 'DNS Proxy 对话框', 64);
   }
}

main();
